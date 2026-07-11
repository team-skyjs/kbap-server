package com.meogo.app.api.member

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.meogo.app.api.auth.FakeSocialTokenVerifierConfig
import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
import com.meogo.infra.persistence.testsupport.RedisContainerConfig
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
class MemberControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val objectMapper = jacksonObjectMapper()

        fun clearMembers() {
            dataSource.connection.use { c -> c.createStatement().use { it.execute("DELETE FROM member") } }
        }

        fun loginAccessToken(): String {
            val response = mockMvc.post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("idToken" to "valid-token"))
            }.andReturn().response
            return objectMapper.readTree(response.contentAsString).path("payload").path("accessToken").asText()
        }

        fun submitOnboarding(token: String?, body: Map<String, Any?>) =
            mockMvc.post("/api/v1/members/me/onboarding") {
                if (token != null) header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }

        fun getMyProfile(token: String?) =
            mockMvc.get("/api/v1/members/me") {
                if (token != null) header("Authorization", "Bearer $token")
            }

        fun updateProfile(token: String?, body: Map<String, Any?>) =
            mockMvc.patch("/api/v1/members/me/profile") {
                if (token != null) header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }

        fun validBody() = mapOf(
            "nickname" to "길동이",
            "avoidanceSubstanceCodes" to listOf("EGG", "MILK"),
            "countryCode" to "US",
            "appLanguage" to "en",
        )

        fun memberColumn(providerUid: String, column: String): String? =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT $column FROM member WHERE provider_uid = ?").use { ps ->
                    ps.setString(1, providerUid)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }

        beforeContainer {
            clearMembers()
        }

        given("온보딩 미완료 회원") {
            `when`("유효한 온보딩 정보를 제출하면") {
                then("200 으로 응답하고 프로필·온보딩 완료가 저장된다") {
                    val token = loginAccessToken()

                    val result = submitOnboarding(token, validBody()).andReturn().response

                    result.status shouldBe 200
                    result.contentAsString shouldContain "\"success\":true"
                    memberColumn("google-sub-fixed", "nickname") shouldBe "길동이"
                    memberColumn("google-sub-fixed", "onboarding_completed") shouldBe "1"
                }
            }

            `when`("이미 온보딩을 완료한 뒤 다시 제출하면") {
                then("400 으로 거절된다") {
                    val token = loginAccessToken()
                    submitOnboarding(token, validBody()).andReturn()

                    val result = submitOnboarding(token, validBody()).andReturn().response

                    result.status shouldBe 400
                    result.contentAsString shouldContain "\"success\":false"
                    result.contentAsString shouldContain "이미 온보딩을 완료했습니다"
                }
            }
        }

        given("무효 입력 온보딩 제출 — 저장 없이 400") {
            listOf(
                "카탈로그에 없는 기피 성분" to validBody() + ("avoidanceSubstanceCodes" to listOf("NOT_A_CODE")),
                "지정 목록에 없는 국가" to validBody() + ("countryCode" to "ZZ"),
                "지원하지 않는 언어" to validBody() + ("appLanguage" to "fr"),
                "빈 닉네임" to validBody() + ("nickname" to "   "),
            ).forEach { (label, body) ->
                `when`(label + "을 제출하면") {
                    then("400 으로 거절되고 프로필·온보딩 상태가 변하지 않는다") {
                        val token = loginAccessToken()

                        val result = submitOnboarding(token, body).andReturn().response

                        result.status shouldBe 400
                        result.contentAsString shouldContain "\"success\":false"
                        memberColumn("google-sub-fixed", "nickname") shouldBe null
                        memberColumn("google-sub-fixed", "onboarding_completed") shouldBe "0"
                    }
                }
            }

            `when`("무효 입력으로 거절된 뒤 유효 입력으로 다시 제출하면") {
                then("200 으로 정상 처리된다") {
                    val token = loginAccessToken()
                    submitOnboarding(token, validBody() + ("countryCode" to "ZZ")).andReturn()

                    val result = submitOnboarding(token, validBody()).andReturn().response

                    result.status shouldBe 200
                    memberColumn("google-sub-fixed", "onboarding_completed") shouldBe "1"
                }
            }
        }

        given("인증 토큰 없는 온보딩 제출") {
            `when`("Authorization 헤더 없이 제출하면") {
                then("401 로 거절된다") {
                    submitOnboarding(null, validBody()).andReturn().response.status shouldBe 401
                }
            }

            `when`("위조된 토큰으로 제출하면") {
                then("401 로 거절된다") {
                    submitOnboarding("forged.access.token", validBody()).andReturn().response.status shouldBe 401
                }
            }
        }

        given("내 프로필 조회") {
            `when`("온보딩을 완료한 회원이 조회하면") {
                then("저장된 프로필과 온보딩 완료 상태가 응답에 담긴다") {
                    val token = loginAccessToken()
                    submitOnboarding(token, validBody()).andReturn()

                    val result = getMyProfile(token).andReturn().response

                    result.status shouldBe 200
                    val payload = objectMapper.readTree(result.contentAsString).path("payload")
                    payload.path("nickname").asText() shouldBe "길동이"
                    payload.path("countryCode").asText() shouldBe "US"
                    payload.path("appLanguage").asText() shouldBe "en"
                    payload.path("onboardingCompleted").asBoolean() shouldBe true
                }
            }

            `when`("온보딩 미완료 회원이 조회하면") {
                then("온보딩 미완료 상태로 응답한다") {
                    val token = loginAccessToken()

                    val result = getMyProfile(token).andReturn().response

                    result.status shouldBe 200
                    val payload = objectMapper.readTree(result.contentAsString).path("payload")
                    payload.path("onboardingCompleted").asBoolean() shouldBe false
                    payload.path("nickname").isNull shouldBe true
                }
            }

            `when`("인증 없이 조회하면") {
                then("401 로 거절된다") {
                    getMyProfile(null).andReturn().response.status shouldBe 401
                }
            }
        }

        given("프로필 수정") {
            fun updateBody() = mapOf(
                "nickname" to "수정닉",
                "avoidanceSubstanceCodes" to listOf("PEANUT"),
                "countryCode" to "JP",
                "appLanguage" to "ja",
            )

            `when`("온보딩을 완료한 회원이 유효한 값으로 수정하면") {
                then("200 으로 응답하고 프로필이 갱신되며 온보딩 상태는 유지된다") {
                    val token = loginAccessToken()
                    submitOnboarding(token, validBody()).andReturn()

                    val result = updateProfile(token, updateBody()).andReturn().response

                    result.status shouldBe 200
                    memberColumn("google-sub-fixed", "nickname") shouldBe "수정닉"
                    memberColumn("google-sub-fixed", "onboarding_completed") shouldBe "1"
                }
            }

            `when`("무효한 값으로 수정하면") {
                then("400 으로 거절된다") {
                    val token = loginAccessToken()
                    submitOnboarding(token, validBody()).andReturn()

                    val result = updateProfile(token, updateBody() + ("countryCode" to "ZZ")).andReturn().response

                    result.status shouldBe 400
                    result.contentAsString shouldContain "\"success\":false"
                }
            }

            `when`("인증 없이 수정하면") {
                then("401 로 거절된다") {
                    updateProfile(null, updateBody()).andReturn().response.status shouldBe 401
                }
            }
        }
    }
}
