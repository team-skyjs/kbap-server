package com.kbap.app.api.e2e

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.app.api.auth.FakeSocialTokenVerifierConfig
import com.kbap.app.api.food.FoodTestSeed
import com.kbap.core.testsupport.MySqlContainerConfig
import com.kbap.core.testsupport.RedisContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
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

/**
 * API 만으로 도는 회원-음식 여정 시나리오.
 * 로그인 → 온보딩(SOY 기피) → 음식 상세 DANGER → 기피 해제 → 같은 음식 SAFE.
 * 리팩토링 시 도메인 내부 구조와 무관하게 기능 동작을 보장하는 안전망.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class, RedisContainerConfig::class, FakeSocialTokenVerifierConfig::class)
class MemberFoodJourneyTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val objectMapper = jacksonObjectMapper()

        fun login(): String {
            val response = mockMvc.post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("idToken" to "valid-token"))
            }.andReturn().response
            return objectMapper.readTree(response.contentAsString).path("payload").path("accessToken").asText()
        }

        fun payload(token: String, path: String) =
            objectMapper.readTree(
                mockMvc.get(path) {
                    header("Authorization", "Bearer $token")
                }.andReturn().response.contentAsString,
            ).path("payload")

        beforeContainer {
            dataSource.connection.use { c -> c.createStatement().use { it.execute("DELETE FROM member") } }
            FoodTestSeed.seedDoenjangStew(dataSource)
        }

        given("로그인한 회원의 온보딩-음식조회-프로필수정 여정") {
            `when`("SOY 기피로 온보딩한 뒤 대두가 든 음식을 조회하고, 기피를 해제한 뒤 다시 조회하면") {
                then("위험도가 DANGER 에서 SAFE 로 바뀐다") {
                    val token = login()

                    mockMvc.post("/api/v1/members/me/onboarding") {
                        header("Authorization", "Bearer $token")
                        contentType = MediaType.APPLICATION_JSON
                        content = objectMapper.writeValueAsString(
                            mapOf(
                                "nickname" to "여정이",
                                "avoidanceSubstanceCodes" to listOf("SOY"),
                                "countryCode" to "US",
                                "appLanguage" to "en",
                            ),
                        )
                    }.andExpect { status { isOk() } }

                    payload(token, "/api/v1/members/me/profile")
                        .path("avoidanceSubstanceCodes").map { it.asText() } shouldBe listOf("SOY")

                    payload(token, "/api/v1/foods/1?lang=en")
                        .path("overallRiskStatus").asText() shouldBe "DANGER"

                    mockMvc.patch("/api/v1/members/me/profile") {
                        header("Authorization", "Bearer $token")
                        contentType = MediaType.APPLICATION_JSON
                        content = objectMapper.writeValueAsString(
                            mapOf("avoidanceSubstanceCodes" to emptyList<String>()),
                        )
                    }.andExpect { status { isOk() } }

                    payload(token, "/api/v1/members/me/profile")
                        .path("avoidanceSubstanceCodes").isEmpty shouldBe true

                    payload(token, "/api/v1/foods/1?lang=en")
                        .path("overallRiskStatus").asText() shouldBe "SAFE"
                }
            }
        }
    }
}
