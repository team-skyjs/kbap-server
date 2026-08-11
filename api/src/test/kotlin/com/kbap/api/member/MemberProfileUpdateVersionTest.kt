package com.kbap.api.member

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.auth.FakeSocialTokenVerifierConfig
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.core.testsupport.RedisContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
class MemberProfileUpdateVersionTest : BehaviorSpec() {
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

        fun updateProfileNoCountry(token: String?, body: Map<String, Any?>, apiVersion: String? = "1.1") =
            mockMvc.patch("/api/v1/members/me/profile") {
                if (token != null) header("Authorization", "Bearer $token")
                if (apiVersion != null) header("X-API-Version", apiVersion)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }

        fun profilePayload(token: String) =
            objectMapper.readTree(
                mockMvc.get("/api/v1/members/me/profile") {
                    header("Authorization", "Bearer $token")
                }.andReturn().response.contentAsString,
            ).path("payload")

        beforeContainer {
            clearMembers()
        }

        given("1.1 프로필 수정 — 국적 변경 불가") {
            `when`("온보딩을 완료한 회원이 닉네임을 수정하면") {
                then("200 으로 응답하고 닉네임은 변경되며 국적은 온보딩 값 그대로다") {
                    val token = onboardedToken()

                    val result = updateProfileNoCountry(token, mapOf("nickname" to "새닉")).andReturn().response

                    result.status shouldBe 200
                    val payload = profilePayload(token)
                    payload.path("nickname").asText() shouldBe "새닉"
                    payload.path("countryCode").asText() shouldBe "US"
                }
            }

            `when`("요청에 countryCode 를 끼워 넣어 보내면") {
                then("200 으로 응답하되 해당 값은 무시되고 국적은 그대로다") {
                    val token = onboardedToken()

                    val result = updateProfileNoCountry(
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

                    val result = updateProfileNoCountry(
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

            `when`("통화를 수정하면") {
                then("통화만 바뀌고 국적은 그대로다") {
                    val token = onboardedToken()

                    val result = updateProfileNoCountry(token, mapOf("currency" to "JPY")).andReturn().response

                    result.status shouldBe 200
                    val payload = profilePayload(token)
                    payload.path("currency").asText() shouldBe "JPY"
                    payload.path("countryCode").asText() shouldBe "US"
                }
            }

            `when`("지원하지 않는 통화 코드를 보내면") {
                then("400 과 MEMBER-010 으로 거절된다") {
                    val token = onboardedToken()

                    val result = updateProfileNoCountry(token, mapOf("currency" to "XXX")).andReturn().response

                    result.status shouldBe 400
                    objectMapper.readTree(result.contentAsString).path("code").asText() shouldBe "MEMBER-010"
                }
            }

            `when`("인증 없이 수정하면") {
                then("401 로 거절된다") {
                    updateProfileNoCountry(null, mapOf("nickname" to "새닉")).andReturn().response.status shouldBe 401
                }
            }
        }

        given("같은 경로를 버전 헤더로만 가르는 라우팅") {
            `when`("X-API-Version 없이 호출하면") {
                then("기본 버전 1.0 으로 해석돼 국적까지 수정되는 계약이 동작한다") {
                    val token = onboardedToken()

                    val result = updateProfileNoCountry(
                        token,
                        mapOf("countryCode" to "JP"),
                        apiVersion = null,
                    ).andReturn().response

                    result.status shouldBe 200
                    profilePayload(token).path("countryCode").asText() shouldBe "JP"
                }
            }

            `when`("X-API-Version 1.1 으로 호출하면") {
                then("같은 요청이라도 국적은 무시된다") {
                    val token = onboardedToken()

                    val result = updateProfileNoCountry(token, mapOf("countryCode" to "JP")).andReturn().response

                    result.status shouldBe 200
                    profilePayload(token).path("countryCode").asText() shouldBe "US"
                }
            }
        }
    }
}
