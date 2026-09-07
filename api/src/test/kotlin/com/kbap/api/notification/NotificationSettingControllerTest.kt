package com.kbap.api.notification

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.auth.FakeSocialTokenVerifier
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class NotificationSettingControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var verifier: FakeSocialTokenVerifier

    init {
        val objectMapper = jacksonObjectMapper()

        data class Consent(val id: Long, val type: String, val version: Int, val grantedAt: String, val revokedAt: String?, val installationId: String?)

        fun memberIdOf(providerUid: String): Long =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT id FROM member WHERE provider_uid = ?").use { ps ->
                    ps.setString(1, providerUid)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else error("회원 없음: $providerUid") }
                }
            }

        fun login(sub: String): Pair<Long, String> {
            val response = mockMvc.post("/api/auth/login") {
                header("X-API-Version", "1.1")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("idToken" to sub))
            }.andReturn().response
            response.status shouldBe 200
            val accessToken = objectMapper.readTree(response.contentAsString).path("payload").path("accessToken").asText()
            return memberIdOf(sub) to accessToken
        }

        fun get(accessToken: String?, apiVersion: String = "1.1"): MockHttpServletResponse =
            mockMvc.get("/api/notifications/settings") {
                header("X-API-Version", apiVersion)
                if (accessToken != null) header("Authorization", "Bearer $accessToken")
            }.andReturn().response

        fun patch(accessToken: String?, body: Any, installationId: String? = null): MockHttpServletResponse =
            mockMvc.patch("/api/notifications/settings") {
                header("X-API-Version", "1.1")
                if (accessToken != null) header("Authorization", "Bearer $accessToken")
                if (installationId != null) header("X-Installation-Id", installationId)
                contentType = MediaType.APPLICATION_JSON
                content = if (body is String) body else objectMapper.writeValueAsString(body)
            }.andReturn().response

        fun payload(response: MockHttpServletResponse): JsonNode {
            response.status shouldBe 200
            return objectMapper.readTree(response.contentAsString).path("payload")
        }

        fun seedSetting(memberId: Long, activity: Boolean, mealTime: Boolean) {
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "INSERT INTO notification_setting (member_id, activity, meal_time, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'ACTIVE', NOW(6), NOW(6))",
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.setBoolean(2, activity)
                    ps.setBoolean(3, mealTime)
                    ps.executeUpdate()
                }
            }
        }

        fun seedConsent(memberId: Long, type: String, version: Int, revoked: Boolean = false) {
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "INSERT INTO notification_consent (member_id, consent_type, consent_version, granted_at, revoked_at, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, NOW(6), ${if (revoked) "NOW(6)" else "NULL"}, 'ACTIVE', NOW(6), NOW(6))",
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.setString(2, type)
                    ps.setInt(3, version)
                    ps.executeUpdate()
                }
            }
        }

        fun consents(memberId: Long): List<Consent> =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT id, consent_type, consent_version, granted_at, revoked_at, installation_id FROM notification_consent WHERE member_id = ? ORDER BY id",
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs else null }
                            .map {
                                Consent(
                                    it.getLong("id"),
                                    it.getString("consent_type"),
                                    it.getInt("consent_version"),
                                    it.getString("granted_at"),
                                    it.getString("revoked_at"),
                                    it.getString("installation_id"),
                                )
                            }
                            .toList()
                    }
                }
            }

        fun countSettings(): Int =
            dataSource.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT COUNT(*) FROM notification_setting").use { rs -> rs.next(); rs.getInt(1) }
                }
            }

        fun enable(privacy: Int? = 1, receive: Int? = 1, mealTime: Boolean? = null): Map<String, Any?> =
            mapOf(
                "news" to buildMap {
                    put("enabled", true)
                    if (privacy != null) put("privacyConsentVersion", privacy)
                    if (receive != null) put("receiveConsentVersion", receive)
                    if (mealTime != null) put("mealTime", mealTime)
                },
            )

        val disable = mapOf("news" to mapOf("enabled" to false))

        fun assertDefault(node: JsonNode) {
            node.path("activity").asBoolean() shouldBe true
            node.path("news").path("enabled").asBoolean() shouldBe false
            node.path("news").path("mealTime").asBoolean() shouldBe false
            node.path("news").path("privacyConsent").isNull shouldBe true
            node.path("news").path("receiveConsent").isNull shouldBe true
        }

        beforeContainer {
            TestTables.clearAll(dataSource)
            verifier.reset()
        }

        given("알림 설정 조회") {
            `when`("설정 기록이 없는 회원이 조회하면") {
                then("기본값으로 응답하고 기록은 만들지 않는다") {
                    val (_, access) = login("member-a")

                    assertDefault(payload(get(access)))
                    countSettings() shouldBe 0
                }
            }

            `when`("활동/소식을 끈 회원이 조회하면") {
                then("활동/소식 꺼짐이 보이고 나머지는 저장된 값대로다") {
                    val (memberId, access) = login("member-a")
                    seedSetting(memberId, activity = false, mealTime = true)

                    val node = payload(get(access))

                    node.path("activity").asBoolean() shouldBe false
                    node.path("news").path("enabled").asBoolean() shouldBe false
                    node.path("news").path("mealTime").asBoolean() shouldBe false
                }
            }

            `when`("두 동의를 모두 한 회원이 조회하면") {
                then("K-Bap 소식 켜짐과 동의 각각의 버전·시각, 식사 시간 알림 값이 보인다") {
                    val (memberId, access) = login("member-a")
                    seedSetting(memberId, activity = true, mealTime = false)
                    seedConsent(memberId, "MARKETING_PRIVACY", 1)
                    seedConsent(memberId, "MARKETING_RECEIVE", 2)

                    val news = payload(get(access)).path("news")

                    news.path("enabled").asBoolean() shouldBe true
                    news.path("mealTime").asBoolean() shouldBe false
                    news.path("privacyConsent").path("version").asInt() shouldBe 1
                    news.path("privacyConsent").path("grantedAt").asText().isNotBlank() shouldBe true
                    news.path("receiveConsent").path("version").asInt() shouldBe 2
                    news.path("receiveConsent").path("grantedAt").asText().isNotBlank() shouldBe true
                }
            }

            `when`("한쪽 동의만 유효한 회원이 조회하면") {
                then("K-Bap 소식은 꺼짐이고 유효한 동의만 채워지며 식사 시간 알림은 저장값과 무관하게 꺼짐이다") {
                    val (memberId, access) = login("member-a")
                    seedSetting(memberId, activity = true, mealTime = true)
                    seedConsent(memberId, "MARKETING_PRIVACY", 1, revoked = true)
                    seedConsent(memberId, "MARKETING_RECEIVE", 1)

                    val news = payload(get(access)).path("news")

                    news.path("enabled").asBoolean() shouldBe false
                    news.path("mealTime").asBoolean() shouldBe false
                    news.path("privacyConsent").isNull shouldBe true
                    news.path("receiveConsent").path("version").asInt() shouldBe 1
                }
            }

            `when`("인증 없이 조회하면") {
                then("401 로 거절된다") {
                    get(null).status shouldBe 401
                }
            }

            `when`("X-API-Version 1.0 으로 조회하면") {
                then("신규 API 라 1.0 부터 동작한다") {
                    val (_, access) = login("member-a")

                    assertDefault(payload(get(access, apiVersion = "1.0")))
                }
            }
        }

        given("토글 수정") {
            `when`("설정 기록이 없는 회원이 활동/소식 끄기를 보내면") {
                then("설정 기록이 생기고 활동/소식 꺼짐, 나머지는 기본값이다") {
                    val (_, access) = login("member-a")

                    val node = payload(patch(access, mapOf("activity" to false)))

                    node.path("activity").asBoolean() shouldBe false
                    node.path("news").path("enabled").asBoolean() shouldBe false
                    node.path("news").path("mealTime").asBoolean() shouldBe false
                    countSettings() shouldBe 1
                }
            }

            `when`("활동/소식이 꺼진 회원이 켜기를 보내면") {
                then("켜짐으로 바뀐다") {
                    val (memberId, access) = login("member-a")
                    seedSetting(memberId, activity = false, mealTime = true)

                    payload(patch(access, mapOf("activity" to true))).path("activity").asBoolean() shouldBe true
                    payload(get(access)).path("activity").asBoolean() shouldBe true
                }
            }

            `when`("K-Bap 소식이 켜진 회원이 식사 시간 알림 끄기만 보내면") {
                then("식사 시간 알림만 꺼지고 활동/소식·동의는 그대로다") {
                    val (memberId, access) = login("member-a")
                    seedConsent(memberId, "MARKETING_PRIVACY", 1)
                    seedConsent(memberId, "MARKETING_RECEIVE", 1)
                    val before = consents(memberId)

                    val node = payload(patch(access, mapOf("news" to mapOf("mealTime" to false))))

                    node.path("activity").asBoolean() shouldBe true
                    node.path("news").path("enabled").asBoolean() shouldBe true
                    node.path("news").path("mealTime").asBoolean() shouldBe false
                    consents(memberId) shouldBe before
                }
            }

            `when`("K-Bap 소식이 꺼진 회원이 식사 시간 알림 켜기를 보내면") {
                then("NOTIFICATION-001 로 거절되고 설정은 바뀌지 않는다") {
                    val (_, access) = login("member-a")

                    val response = patch(access, mapOf("news" to mapOf("mealTime" to true)))

                    response.status shouldBe 400
                    response.contentAsString shouldContain "NOTIFICATION-001"
                    countSettings() shouldBe 0
                }
            }

            `when`("K-Bap 소식이 꺼진 회원이 식사 시간 알림 끄기를 보내면") {
                then("끄기는 허용되고 값이 보존된다") {
                    val (_, access) = login("member-a")

                    payload(patch(access, mapOf("news" to mapOf("mealTime" to false)))).path("news").path("mealTime").asBoolean() shouldBe false
                    countSettings() shouldBe 1
                }
            }

            `when`("빈 본문을 보내면") {
                then("아무것도 바뀌지 않고 현재 설정을 응답한다") {
                    val (_, access) = login("member-a")

                    assertDefault(payload(patch(access, "{}")))
                    countSettings() shouldBe 0
                }
            }

            `when`("인증 없이 수정하면") {
                then("401 로 거절된다") {
                    patch(null, mapOf("activity" to false)).status shouldBe 401
                }
            }
        }

        given("K-Bap 소식 동의") {
            `when`("동의 기록이 없는 회원이 두 문구 버전과 함께 켜기를 보내면") {
                then("종류별 열린 동의가 하나씩 생기고 식사 시간 알림이 켜진다") {
                    val (memberId, access) = login("member-a")

                    val news = payload(patch(access, enable(1, 1))).path("news")

                    news.path("enabled").asBoolean() shouldBe true
                    news.path("mealTime").asBoolean() shouldBe true
                    news.path("privacyConsent").path("version").asInt() shouldBe 1
                    news.path("receiveConsent").path("version").asInt() shouldBe 1
                    news.path("privacyConsent").path("grantedAt").asText().isNotBlank() shouldBe true
                    val rows = consents(memberId)
                    rows.size shouldBe 2
                    rows.map { it.type }.toSet() shouldBe setOf("MARKETING_PRIVACY", "MARKETING_RECEIVE")
                    rows.all { it.revokedAt == null && it.version == 1 } shouldBe true
                }
            }

            `when`("켜기를 보내는데 두 버전 중 하나라도 없으면") {
                then("400 COMMON-002 로 거절되고 원장·설정은 바뀌지 않는다") {
                    val (memberId, access) = login("member-a")

                    val response = patch(access, enable(privacy = 1, receive = null))

                    response.status shouldBe 400
                    response.contentAsString shouldContain "COMMON-002"
                    patch(access, enable(privacy = null, receive = 1)).status shouldBe 400
                    consents(memberId).size shouldBe 0
                    countSettings() shouldBe 0
                }
            }

            `when`("같은 버전들로 다시 켜기를 보내면") {
                then("원장·설정 모두 바뀌지 않는다") {
                    val (memberId, access) = login("member-a")
                    patch(access, enable(1, 1))
                    val first = consents(memberId)

                    payload(patch(access, enable(1, 1)))

                    consents(memberId) shouldBe first
                }
            }

            `when`("광고성 정보 수신 동의 버전만 올려 켜기를 보내면") {
                then("수신 동의는 이전 기록이 닫히고 새 버전 기록이 생기며 개인정보 동의는 그대로다") {
                    val (memberId, access) = login("member-a")
                    patch(access, enable(1, 1))
                    val privacyBefore = consents(memberId).single { it.type == "MARKETING_PRIVACY" }

                    val news = payload(patch(access, enable(1, 2))).path("news")

                    news.path("receiveConsent").path("version").asInt() shouldBe 2
                    val byType = consents(memberId).groupBy { it.type }
                    byType.getValue("MARKETING_PRIVACY").single() shouldBe privacyBefore
                    val receive = byType.getValue("MARKETING_RECEIVE")
                    receive.size shouldBe 2
                    receive[0].version shouldBe 1
                    receive[0].revokedAt.shouldNotBeNull()
                    receive[1].version shouldBe 2
                    receive[1].revokedAt.shouldBeNull()
                }
            }

            `when`("식사 시간 알림을 끈 회원이 K-Bap 소식 끄기를 보내면") {
                then("두 종류의 열린 기록이 모두 닫히고 행은 남으며 식사 시간 알림 값은 보존된다") {
                    val (memberId, access) = login("member-a")
                    patch(access, enable(1, 1))
                    patch(access, mapOf("news" to mapOf("mealTime" to false)))
                    val total = consents(memberId).size

                    val news = payload(patch(access, disable)).path("news")

                    news.path("enabled").asBoolean() shouldBe false
                    news.path("mealTime").asBoolean() shouldBe false
                    news.path("privacyConsent").isNull shouldBe true
                    news.path("receiveConsent").isNull shouldBe true
                    val rows = consents(memberId)
                    rows.size shouldBe total
                    rows.all { it.revokedAt != null } shouldBe true
                }
            }

            `when`("동의 기록이 없는 회원이 끄기를 보내면") {
                then("아무 변화 없이 성공한다") {
                    val (memberId, access) = login("member-a")

                    assertDefault(payload(patch(access, disable)))
                    consents(memberId).size shouldBe 0
                }
            }

            `when`("켜기 요청에 클라이언트 동의 시각을 실어 보내면") {
                then("그 값은 무시되고 서버 시각이 기록된다") {
                    val (_, access) = login("member-a")
                    val body = """{"news":{"enabled":true,"privacyConsentVersion":1,"receiveConsentVersion":1,"grantedAt":"2000-01-01T00:00:00"}}"""

                    val news = payload(patch(access, body)).path("news")

                    news.path("privacyConsent").path("grantedAt").asText() shouldNotBe "2000-01-01T00:00:00"
                }
            }

            `when`("K-Bap 소식을 껐다가 다시 켜면") {
                then("식사 시간 알림 꺼짐이 그대로 복원된다") {
                    val (_, access) = login("member-a")
                    patch(access, enable(1, 1))
                    patch(access, mapOf("news" to mapOf("mealTime" to false)))
                    patch(access, disable)

                    val news = payload(patch(access, enable(1, 1))).path("news")

                    news.path("enabled").asBoolean() shouldBe true
                    news.path("mealTime").asBoolean() shouldBe false
                }
            }

            `when`("켜기와 식사 시간 알림 값을 한 요청에 보내면") {
                then("켜기가 먼저 반영돼 식사 시간 알림 값도 저장된다") {
                    val (_, access) = login("member-a")

                    val news = payload(patch(access, enable(1, 1, mealTime = false))).path("news")

                    news.path("enabled").asBoolean() shouldBe true
                    news.path("mealTime").asBoolean() shouldBe false
                    payload(patch(access, mapOf("news" to mapOf("mealTime" to true)))).path("news").path("mealTime").asBoolean() shouldBe true
                }
            }

            `when`("기기 식별자 헤더가 공백이거나 36자를 넘으면") {
                then("400 COMMON-002 로 거절되고 원장은 바뀌지 않는다") {
                    val (memberId, access) = login("member-a")

                    val tooLong = patch(access, enable(1, 1), installationId = "x".repeat(37))
                    tooLong.status shouldBe 400
                    tooLong.contentAsString shouldContain "COMMON-002"
                    patch(access, enable(1, 1), installationId = " ").status shouldBe 400
                    consents(memberId).size shouldBe 0
                }
            }

            `when`("기기 식별자 헤더와 함께 켜면") {
                then("동의 기록에 동의 받은 기기가 남는다") {
                    val (memberId, access) = login("member-a")

                    payload(patch(access, enable(1, 1), installationId = "dev-1"))

                    consents(memberId).all { it.installationId == "dev-1" } shouldBe true
                }
            }
        }
    }
}
