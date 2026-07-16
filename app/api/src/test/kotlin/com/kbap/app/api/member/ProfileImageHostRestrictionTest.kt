package com.kbap.app.api.member

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.app.api.auth.FakeSocialTokenVerifierConfig
import com.kbap.core.testsupport.MySqlContainerConfig
import com.kbap.core.testsupport.RedisContainerConfig
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
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest(properties = ["kbap.member.profile-image-allowed-hosts=cdn.kbap.test,cdn2.kbap.test"])
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class, RedisContainerConfig::class, FakeSocialTokenVerifierConfig::class)
class ProfileImageHostRestrictionTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val objectMapper = jacksonObjectMapper()

        fun loginAccessToken(): String {
            val response = mockMvc.post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("idToken" to "valid-token"))
            }.andReturn().response
            return objectMapper.readTree(response.contentAsString).path("payload").path("accessToken").asText()
        }

        fun submitOnboarding(token: String, profileImageUrl: String) =
            mockMvc.post("/api/v1/members/me/onboarding") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(
                    mapOf(
                        "nickname" to "길동이",
                        "avoidanceSubstanceCodes" to emptyList<String>(),
                        "countryCode" to "US",
                        "appLanguage" to "en",
                        "spicinessPreference" to 3,
                        "profileImageUrl" to profileImageUrl,
                    ),
                )
            }.andReturn().response

        beforeContainer {
            dataSource.connection.use { c -> c.createStatement().use { it.execute("DELETE FROM member") } }
        }

        given("허용 이미지 호스트 목록이 설정된 환경") {
            `when`("허용 호스트의 사진 URL 로 온보딩하면") {
                then("정상 처리된다") {
                    val token = loginAccessToken()

                    val result = submitOnboarding(token, "https://cdn2.kbap.test/profiles/abc.jpg")

                    io.kotest.assertions.withClue(result.contentAsString) {
                        result.status shouldBe 200
                    }
                }
            }

            `when`("허용 목록에 없는 호스트의 사진 URL 로 온보딩하면") {
                then("400 MEMBER-008 로 거절된다") {
                    val token = loginAccessToken()

                    val result = submitOnboarding(token, "https://evil.example.com/profiles/abc.jpg")

                    result.status shouldBe 400
                    result.contentAsString shouldContain "MEMBER-008"
                }
            }
        }
    }
}
