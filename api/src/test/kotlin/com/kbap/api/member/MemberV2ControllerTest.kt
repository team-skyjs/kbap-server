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
    }
}
