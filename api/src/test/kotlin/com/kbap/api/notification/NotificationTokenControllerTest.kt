package com.kbap.api.notification

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.auth.FakeSocialTokenVerifier
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import javax.sql.DataSource

@IntegrationTest
class NotificationTokenControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var verifier: FakeSocialTokenVerifier

    init {
        val objectMapper = jacksonObjectMapper()

        data class Device(val memberId: Long?, val token: String, val platform: String, val lang: String, val invalidAt: String?)

        fun body(
            token: String = "ExponentPushToken[abc]",
            platform: String = "ios",
            lang: String = "en",
            extra: Map<String, Any?> = emptyMap(),
        ): Map<String, Any?> = mapOf("token" to token, "platform" to platform, "lang" to lang) + extra

        fun register(
            installationId: String?,
            accessToken: String?,
            body: Map<String, Any?> = body(),
            apiVersion: String? = "1.1",
        ): MockHttpServletResponse =
            mockMvc.put("/api/notifications/tokens") {
                if (apiVersion != null) header("X-API-Version", apiVersion)
                if (installationId != null) header("X-Installation-Id", installationId)
                if (accessToken != null) header("Authorization", "Bearer $accessToken")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }.andReturn().response

        fun memberIdOf(providerUid: String): Long =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT id FROM member WHERE provider_uid = ?").use { ps ->
                    ps.setString(1, providerUid)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else error("회원 없음: $providerUid") }
                }
            }

        fun login(sub: String): Pair<Long, String> {
            val response = mockMvc.post("/api/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("idToken" to sub))
            }.andReturn().response
            val accessToken = objectMapper.readTree(response.contentAsString).path("payload").path("accessToken").asText()
            return memberIdOf(sub) to accessToken
        }

        fun device(installationId: String): Device? =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT member_id, expo_token, platform, lang, token_invalid_at FROM notification_device WHERE installation_id = ?",
                ).use { ps ->
                    ps.setString(1, installationId)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) return null
                        Device(
                            rs.getObject("member_id")?.let { (it as Number).toLong() },
                            rs.getString("expo_token"),
                            rs.getString("platform"),
                            rs.getString("lang"),
                            rs.getString("token_invalid_at"),
                        )
                    }
                }
            }

        fun count(table: String): Int =
            dataSource.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getInt(1) }
                }
            }

        fun countDevices(): Int = count("notification_device")

        fun countConsents(): Int = count("notification_consent")

        fun markTokenInvalid(installationId: String) {
            dataSource.connection.use { c ->
                c.prepareStatement("UPDATE notification_device SET token_invalid_at = NOW(6) WHERE installation_id = ?").use { ps ->
                    ps.setString(1, installationId)
                    ps.executeUpdate()
                }
            }
        }

        beforeContainer {
            TestTables.clearAll(dataSource)
            verifier.reset()
        }

        given("기기 토큰 등록") {
            `when`("로그인하지 않은 앱이 토큰·플랫폼·언어를 보내면") {
                then("401 로 거절되고 기기·동의 기록이 생기지 않는다") {
                    register("dev-1", accessToken = null).status shouldBe 401

                    countDevices() shouldBe 0
                    countConsents() shouldBe 0
                }
            }

            `when`("서버가 모르는 기기가 회원 인증과 함께 토큰·플랫폼·언어를 보내면") {
                then("기기 기록이 하나 생기고 요청 회원에 연결된다") {
                    val (memberId, accessToken) = login("member-a")

                    register("dev-1", accessToken).status shouldBe 200

                    countDevices() shouldBe 1
                    val saved = device("dev-1").shouldNotBeNull()
                    saved.memberId shouldBe memberId
                    saved.token shouldBe "ExponentPushToken[abc]"
                    saved.platform shouldBe "IOS"
                    saved.lang shouldBe "en"
                    saved.invalidAt.shouldBeNull()
                }
            }

            `when`("이미 등록된 기기가 다른 토큰·플랫폼·언어를 보내면") {
                then("기록 수는 그대로이고 세 값이 새 값으로 바뀐다") {
                    val (_, accessToken) = login("member-a")
                    register("dev-1", accessToken)

                    register("dev-1", accessToken, body = body(token = "ExponentPushToken[new]", platform = "android", lang = "ko")).status shouldBe 200

                    countDevices() shouldBe 1
                    val saved = device("dev-1").shouldNotBeNull()
                    saved.token shouldBe "ExponentPushToken[new]"
                    saved.platform shouldBe "ANDROID"
                    saved.lang shouldBe "ko"
                }
            }

            `when`("토큰이 무효로 표시된 기기가 새 토큰을 보내면") {
                then("무효 표시가 사라진다") {
                    val (_, accessToken) = login("member-a")
                    register("dev-1", accessToken)
                    markTokenInvalid("dev-1")
                    device("dev-1").shouldNotBeNull().invalidAt.shouldNotBeNull()

                    register("dev-1", accessToken, body = body(token = "ExponentPushToken[fresh]"))

                    device("dev-1").shouldNotBeNull().invalidAt.shouldBeNull()
                }
            }

            `when`("다른 회원에 연결된 기기에서 새 회원이 토큰을 보내면") {
                then("같은 기록의 연결 회원이 바뀌고 기록 수는 늘지 않는다") {
                    val (_, tokenA) = login("member-a")
                    register("dev-1", tokenA)
                    val (memberB, tokenB) = login("member-b")

                    register("dev-1", tokenB).status shouldBe 200

                    countDevices() shouldBe 1
                    device("dev-1").shouldNotBeNull().memberId shouldBe memberB
                }
            }

            `when`("같은 요청을 두 번 연속 보내면") {
                then("기록 수와 값이 첫 번째와 같다") {
                    val (_, accessToken) = login("member-a")
                    register("dev-1", accessToken)
                    val afterFirst = device("dev-1")

                    register("dev-1", accessToken)

                    device("dev-1") shouldBe afterFirst
                    countDevices() shouldBe 1
                }
            }

            `when`("예전 계약의 게스트 동의 필드를 실어 보내면") {
                then("무시되어 토큰만 등록되고 동의 원장은 비어 있다") {
                    val (memberId, accessToken) = login("member-a")
                    val legacySettings = mapOf("settings" to mapOf("marketing" to true, "privacyConsentVersion" to 1, "receiveConsentVersion" to 1))

                    register("dev-1", accessToken, body = body(extra = legacySettings)).status shouldBe 200

                    countConsents() shouldBe 0
                    device("dev-1").shouldNotBeNull().memberId shouldBe memberId
                }
            }
        }

        given("잘못된 등록 요청") {
            `when`("기기 식별자 헤더가 없으면") {
                then("400 COMMON-002 로 거절되고 기록이 생기지 않는다") {
                    val (_, accessToken) = login("member-a")

                    val response = register(null, accessToken)

                    response.status shouldBe 400
                    response.contentAsString shouldContain "COMMON-002"
                    countDevices() shouldBe 0
                }
            }

            `when`("기기 식별자 헤더가 빈 문자열이면") {
                then("400 으로 거절된다") {
                    val (_, accessToken) = login("member-a")
                    register("", accessToken).status shouldBe 400
                    countDevices() shouldBe 0
                }
            }

            `when`("토큰이 공백이면") {
                then("400 으로 거절된다") {
                    val (_, accessToken) = login("member-a")
                    register("dev-1", accessToken, body = body(token = " ")).status shouldBe 400
                    countDevices() shouldBe 0
                }
            }

            `when`("플랫폼이 허용 값이 아니면") {
                then("400 으로 거절된다") {
                    val (_, accessToken) = login("member-a")
                    register("dev-1", accessToken, body = body(platform = "web")).status shouldBe 400
                    countDevices() shouldBe 0
                }
            }

            `when`("언어가 공백이면") {
                then("400 으로 거절된다") {
                    val (_, accessToken) = login("member-a")
                    register("dev-1", accessToken, body = body(lang = "")).status shouldBe 400
                    countDevices() shouldBe 0
                }
            }

            `when`("위조된 인증 토큰이 붙어 있으면") {
                then("401 로 거절된다") {
                    register("dev-1", accessToken = "garbage").status shouldBe 401
                    countDevices() shouldBe 0
                }
            }

            `when`("플랫폼을 대문자로 보내면") {
                then("정상 등록된다") {
                    val (_, accessToken) = login("member-a")
                    register("dev-1", accessToken, body = body(platform = "IOS")).status shouldBe 200
                    device("dev-1").shouldNotBeNull().platform shouldBe "IOS"
                }
            }

            `when`("탈퇴한 회원의 아직 만료되지 않은 인증 토큰이 붙어 있으면") {
                then("400 MEMBER-003 으로 거절되고 기기는 연결되지 않는다") {
                    val (_, accessToken) = login("member-a")
                    mockMvc.patch("/api/auth/withdraw") { header("Authorization", "Bearer $accessToken") }
                        .andReturn().response.status shouldBe 200

                    val response = register("dev-1", accessToken)

                    response.status shouldBe 400
                    response.contentAsString shouldContain "MEMBER-003"
                    countDevices() shouldBe 0
                }
            }

            `when`("X-API-Version 1.0 으로 보내면") {
                then("존재하지 않는 API 로 404 거절되고 기록이 생기지 않는다") {
                    val (_, accessToken) = login("member-a")
                    register("dev-1", accessToken, apiVersion = "1.0").status shouldBe 404
                    countDevices() shouldBe 0
                }
            }
        }
    }
}
