package com.kbap.api.member

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.auth.FakeSocialTokenVerifierConfig
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.core.testsupport.RedisContainerConfig
import com.kbap.common.domain.member.model.OnboardingProfileDefaults
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class, RedisContainerConfig::class, FakeSocialTokenVerifierConfig::class)
class MemberV2ControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val objectMapper = jacksonObjectMapper()

        fun clearMembers() {
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.execute("DELETE FROM member_block")
                    it.execute("DELETE FROM community_comment WHERE parent_id IS NOT NULL")
                    it.execute("DELETE FROM community_comment")
                    it.execute("DELETE FROM community_post")
                    it.execute("DELETE FROM member")
                }
            }
        }

        fun loginAccessToken(): String {
            val response = mockMvc.post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("idToken" to "valid-token"))
            }.andReturn().response
            return objectMapper.readTree(response.contentAsString).path("payload").path("accessToken").asText()
        }

        fun onboardedToken(): String {
            val token = loginAccessToken()
            mockMvc.post("/api/v1/members/me/onboarding") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(
                    mapOf(
                        "nickname" to "길동이",
                        "avoidanceSubstanceCodes" to listOf("EGG", "MILK"),
                        "countryCode" to "US",
                        "spicinessPreference" to "SKIP",
                        "profileImageUrl" to "images/default/profile/profile-default-512.png",
                    ),
                )
            }.andReturn()
            return token
        }

        fun updateProfileV2(token: String?, body: Map<String, Any?>) =
            mockMvc.patch("/api/v2/members/me/profile") {
                if (token != null) header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }

        fun submitOnboardingV2(token: String?, body: Map<String, Any?>) =
            mockMvc.post("/api/v2/members/me/onboarding") {
                if (token != null) header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }

        fun validV2OnboardingBody() = mapOf(
            "avoidanceSubstanceCodes" to listOf("EGG", "MILK"),
            "countryCode" to "US",
            "spicinessPreference" to "SKIP",
        )

        fun profilePayload(token: String) =
            objectMapper.readTree(
                mockMvc.get("/api/v1/members/me/profile") {
                    header("Authorization", "Bearer $token")
                }.andReturn().response.contentAsString,
            ).path("payload")

        beforeContainer {
            clearMembers()
        }

        given("v2 프로필 수정 — 국적 변경 불가") {
            `when`("온보딩을 완료한 회원이 닉네임을 수정하면") {
                then("200 으로 응답하고 닉네임은 변경되며 국적은 온보딩 값 그대로다") {
                    val token = onboardedToken()

                    val result = updateProfileV2(token, mapOf("nickname" to "새닉")).andReturn().response

                    result.status shouldBe 200
                    val payload = profilePayload(token)
                    payload.path("nickname").asText() shouldBe "새닉"
                    payload.path("countryCode").asText() shouldBe "US"
                }
            }

            `when`("요청에 countryCode 를 끼워 넣어 보내면") {
                then("200 으로 응답하되 해당 값은 무시되고 국적은 그대로다") {
                    val token = onboardedToken()

                    val result = updateProfileV2(
                        token,
                        mapOf("nickname" to "새닉", "countryCode" to "JP"),
                    ).andReturn().response

                    result.status shouldBe 200
                    val payload = profilePayload(token)
                    payload.path("nickname").asText() shouldBe "새닉"
                    payload.path("countryCode").asText() shouldBe "US"
                }
            }

            `when`("기피 성분·프로필 이미지·맵기 선호를 수정하면") {
                then("각 항목은 반영되고 국적은 그대로다") {
                    val token = onboardedToken()

                    val result = updateProfileV2(
                        token,
                        mapOf(
                            "avoidanceSubstanceCodes" to listOf("PEANUT"),
                            "profileImageUrl" to "images/default/profile/profile-default-256.png",
                            "spicinessPreference" to "MILD",
                        ),
                    ).andReturn().response

                    result.status shouldBe 200
                    val payload = profilePayload(token)
                    payload.path("avoidanceSubstanceCodes").map { it.asText() } shouldBe listOf("PEANUT")
                    payload.path("profileImageUrl").asText() shouldContain "profile-default-256"
                    payload.path("spicinessPreference").asText() shouldBe "MILD"
                    payload.path("countryCode").asText() shouldBe "US"
                }
            }

            `when`("인증 없이 수정하면") {
                then("401 로 거절된다") {
                    updateProfileV2(null, mapOf("nickname" to "새닉")).andReturn().response.status shouldBe 401
                }
            }
        }

        given("v2 온보딩 — 닉네임·프로필 사진을 입력받지 않음") {
            `when`("닉네임·사진 없이 회피 성분·국가·맵기만 담아 온보딩하면") {
                then("200 으로 응답하고 닉네임은 코드 형식으로, 사진은 후보 중 하나로 지정된다") {
                    val token = loginAccessToken()

                    val result = submitOnboardingV2(token, validV2OnboardingBody()).andReturn().response

                    result.status shouldBe 200
                    val payload = profilePayload(token)
                    payload.path("nickname").asText() shouldMatch Regex("^[A-HJ-NP-Z2-9]{6}$")
                    val imageUrl = payload.path("profileImageUrl").asText()
                    OnboardingProfileDefaults.PROFILE_IMAGE_PATHS.any { imageUrl.endsWith(it) } shouldBe true
                    payload.path("countryCode").asText() shouldBe "US"
                }
            }

            `when`("요청에 nickname·profileImageUrl 을 끼워 넣어 보내면") {
                then("그 값은 무시되고 서버가 지정한 값이 저장된다") {
                    val token = loginAccessToken()

                    val result = submitOnboardingV2(
                        token,
                        validV2OnboardingBody() + mapOf(
                            "nickname" to "내가정한닉",
                            "profileImageUrl" to "images/default/profile/profile-default-512.png",
                        ),
                    ).andReturn().response

                    result.status shouldBe 200
                    val payload = profilePayload(token)
                    payload.path("nickname").asText() shouldNotBe "내가정한닉"
                    payload.path("nickname").asText() shouldMatch Regex("^[A-HJ-NP-Z2-9]{6}$")
                    val imageUrl = payload.path("profileImageUrl").asText()
                    OnboardingProfileDefaults.PROFILE_IMAGE_PATHS.any { imageUrl.endsWith(it) } shouldBe true
                }
            }

            `when`("필수 항목인 countryCode 를 누락하면") {
                then("400 COMMON-002 로 거절된다") {
                    val token = loginAccessToken()

                    val result = submitOnboardingV2(
                        token,
                        validV2OnboardingBody() - "countryCode",
                    ).andReturn().response

                    result.status shouldBe 400
                    result.contentAsString shouldContain "COMMON-002"
                }
            }

            `when`("이미 온보딩을 완료한 뒤 다시 제출하면") {
                then("400 MEMBER-002 로 거절된다") {
                    val token = loginAccessToken()
                    submitOnboardingV2(token, validV2OnboardingBody()).andReturn()

                    val result = submitOnboardingV2(token, validV2OnboardingBody()).andReturn().response

                    result.status shouldBe 400
                    result.contentAsString shouldContain "MEMBER-002"
                }
            }

            `when`("인증 없이 온보딩하면") {
                then("401 로 거절된다") {
                    submitOnboardingV2(null, validV2OnboardingBody()).andReturn().response.status shouldBe 401
                }
            }
        }

        given("온보딩 경로가 달라도 이후 동작은 동일") {
            `when`("v2 로 온보딩한 회원이 프로필 수정으로 닉네임·사진을 바꾸면") {
                then("자동 지정값이 사용자 지정값으로 교체된다") {
                    val token = loginAccessToken()
                    submitOnboardingV2(token, validV2OnboardingBody()).andReturn()
                    val assigned = profilePayload(token).path("nickname").asText()

                    val result = updateProfileV2(
                        token,
                        mapOf(
                            "nickname" to "내가정한닉",
                            "profileImageUrl" to "images/default/profile/profile-default-512.png",
                        ),
                    ).andReturn().response

                    result.status shouldBe 200
                    val payload = profilePayload(token)
                    payload.path("nickname").asText() shouldBe "내가정한닉"
                    payload.path("nickname").asText() shouldNotBe assigned
                    payload.path("profileImageUrl").asText() shouldContain "profile-default-512"
                }
            }

            // FakeSocialTokenVerifier 가 항상 같은 provider_uid 를 돌려주므로 두 회원을 만들려면 중간에 비워야 한다.
            `when`("v2 회원과 v1 회원의 프로필을 각각 조회하면") {
                then("같은 필드 집합과 같은 형태의 사진 주소가 반환된다") {
                    val v2Token = loginAccessToken()
                    submitOnboardingV2(v2Token, validV2OnboardingBody()).andReturn()
                    val v2Payload = profilePayload(v2Token)

                    clearMembers()
                    val v1Payload = profilePayload(onboardedToken())

                    v2Payload.fieldNames().asSequence().toSet() shouldBe
                        v1Payload.fieldNames().asSequence().toSet()
                    v2Payload.path("profileImageUrl").asText() shouldStartWith "http"
                    v1Payload.path("profileImageUrl").asText() shouldStartWith "http"
                    v2Payload.path("countryCode").asText() shouldBe v1Payload.path("countryCode").asText()
                }
            }
        }
    }
}
