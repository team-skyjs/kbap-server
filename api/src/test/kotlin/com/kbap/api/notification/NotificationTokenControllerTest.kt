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

        data class Consent(val memberId: Long?, val version: Int, val grantedAt: String, val revokedAt: String?)

        fun body(
            token: String = "ExponentPushToken[abc]",
            platform: String = "ios",
            lang: String = "en",
            settings: Map<String, Any?>? = null,
        ): Map<String, Any?> = buildMap {
            put("token", token)
            put("platform", platform)
            put("lang", lang)
            if (settings != null) put("settings", settings)
        }

        fun register(
            installationId: String?,
            accessToken: String? = null,
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

        fun countDevices(): Int =
            dataSource.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT COUNT(*) FROM notification_device").use { rs -> rs.next(); rs.getInt(1) }
                }
            }

        fun consents(installationId: String): List<Consent> =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT member_id, consent_version, granted_at, revoked_at FROM notification_consent WHERE installation_id = ? ORDER BY id",
                ).use { ps ->
                    ps.setString(1, installationId)
                    ps.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs else null }
                            .map {
                                Consent(
                                    it.getObject("member_id")?.let { id -> (id as Number).toLong() },
                                    it.getInt("consent_version"),
                                    it.getString("granted_at"),
                                    it.getString("revoked_at"),
                                )
                            }
                            .toList()
                    }
                }
            }

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
            `when`("서버가 모르는 기기가 게스트로 토큰·플랫폼·언어를 보내면") {
                then("기기 기록이 하나 생기고 회원 연결은 비어 있다") {
                    register("dev-1").status shouldBe 200

                    countDevices() shouldBe 1
                    val saved = device("dev-1").shouldNotBeNull()
                    saved.memberId.shouldBeNull()
                    saved.token shouldBe "ExponentPushToken[abc]"
                    saved.platform shouldBe "IOS"
                    saved.lang shouldBe "en"
                    saved.invalidAt.shouldBeNull()
                }
            }

            `when`("이미 등록된 기기가 다른 토큰·플랫폼·언어를 보내면") {
                then("기록 수는 그대로이고 세 값이 새 값으로 바뀐다") {
                    register("dev-1")
                    register("dev-1", body = body(token = "ExponentPushToken[new]", platform = "android", lang = "ko")).status shouldBe 200

                    countDevices() shouldBe 1
                    val saved = device("dev-1").shouldNotBeNull()
                    saved.token shouldBe "ExponentPushToken[new]"
                    saved.platform shouldBe "ANDROID"
                    saved.lang shouldBe "ko"
                }
            }

            `when`("토큰이 무효로 표시된 기기가 새 토큰을 보내면") {
                then("무효 표시가 사라진다") {
                    register("dev-1")
                    markTokenInvalid("dev-1")
                    device("dev-1").shouldNotBeNull().invalidAt.shouldNotBeNull()

                    register("dev-1", body = body(token = "ExponentPushToken[fresh]"))

                    device("dev-1").shouldNotBeNull().invalidAt.shouldBeNull()
                }
            }

            `when`("게스트로 등록된 기기에 회원 인증을 붙여 다시 보내면") {
                then("같은 기록에 회원이 연결되고 기록 수는 늘지 않는다") {
                    register("dev-1")
                    val (memberId, accessToken) = login("member-a")

                    register("dev-1", accessToken = accessToken).status shouldBe 200

                    countDevices() shouldBe 1
                    device("dev-1").shouldNotBeNull().memberId shouldBe memberId
                }
            }

            `when`("회원에 연결된 기기가 게스트로 다시 보내면") {
                then("회원 연결은 유지된다") {
                    val (memberId, accessToken) = login("member-a")
                    register("dev-1", accessToken = accessToken)

                    register("dev-1", body = body(token = "ExponentPushToken[again]"))

                    device("dev-1").shouldNotBeNull().memberId shouldBe memberId
                }
            }

            `when`("같은 요청을 두 번 연속 보내면") {
                then("게스트·회원 모두 기록 수와 값이 첫 번째와 같다") {
                    register("dev-guest")
                    val afterFirst = device("dev-guest")
                    register("dev-guest")
                    device("dev-guest") shouldBe afterFirst

                    val (_, accessToken) = login("member-a")
                    register("dev-member", accessToken = accessToken)
                    val afterMemberFirst = device("dev-member")
                    register("dev-member", accessToken = accessToken)
                    device("dev-member") shouldBe afterMemberFirst

                    countDevices() shouldBe 2
                }
            }
        }

        given("잘못된 등록 요청") {
            `when`("기기 식별자 헤더가 없으면") {
                then("400 COMMON-002 로 거절되고 기록이 생기지 않는다") {
                    val response = register(null)

                    response.status shouldBe 400
                    response.contentAsString shouldContain "COMMON-002"
                    countDevices() shouldBe 0
                }
            }

            `when`("기기 식별자 헤더가 빈 문자열이면") {
                then("400 으로 거절된다") {
                    register("").status shouldBe 400
                    countDevices() shouldBe 0
                }
            }

            `when`("토큰이 공백이면") {
                then("400 으로 거절된다") {
                    register("dev-1", body = body(token = " ")).status shouldBe 400
                    countDevices() shouldBe 0
                }
            }

            `when`("플랫폼이 허용 값이 아니면") {
                then("400 으로 거절된다") {
                    register("dev-1", body = body(platform = "web")).status shouldBe 400
                    countDevices() shouldBe 0
                }
            }

            `when`("언어가 공백이면") {
                then("400 으로 거절된다") {
                    register("dev-1", body = body(lang = "")).status shouldBe 400
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
                    register("dev-1", body = body(platform = "IOS")).status shouldBe 200
                    device("dev-1").shouldNotBeNull().platform shouldBe "IOS"
                }
            }

            `when`("X-API-Version 1.0 으로 보내면") {
                then("존재하지 않는 API 로 404 거절되고 기록이 생기지 않는다") {
                    register("dev-1", apiVersion = "1.0").status shouldBe 404
                    countDevices() shouldBe 0
                }
            }
        }
    }
}
