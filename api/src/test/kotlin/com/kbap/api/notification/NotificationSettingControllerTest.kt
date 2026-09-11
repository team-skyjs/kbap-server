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

        data class SettingRow(val installationId: String?, val activity: Boolean, val mealTime: Boolean, val news: Boolean)

        fun getDevice(accessToken: String?, installationId: String?, apiVersion: String = "2.1"): MockHttpServletResponse =
            mockMvc.get("/api/notifications/settings") {
                header("X-API-Version", apiVersion)
                if (accessToken != null) header("Authorization", "Bearer $accessToken")
                if (installationId != null) header("X-Installation-Id", installationId)
            }.andReturn().response

        fun patchDevice(accessToken: String?, installationId: String?, body: Any, apiVersion: String = "2.1"): MockHttpServletResponse =
            mockMvc.patch("/api/notifications/settings") {
                header("X-API-Version", apiVersion)
                if (accessToken != null) header("Authorization", "Bearer $accessToken")
                if (installationId != null) header("X-Installation-Id", installationId)
                contentType = MediaType.APPLICATION_JSON
                content = if (body is String) body else objectMapper.writeValueAsString(body)
            }.andReturn().response

        fun seedDeviceSetting(memberId: Long, installationId: String, activity: Boolean = false, mealTime: Boolean = false, news: Boolean = false) {
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "INSERT INTO notification_setting (member_id, installation_id, activity, meal_time, news, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW(6), NOW(6))",
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.setString(2, installationId)
                    ps.setBoolean(3, activity)
                    ps.setBoolean(4, mealTime)
                    ps.setBoolean(5, news)
                    ps.executeUpdate()
                }
            }
        }

        fun settingRows(memberId: Long): List<SettingRow> =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT installation_id, activity, meal_time, news FROM notification_setting WHERE member_id = ? ORDER BY installation_id",
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs else null }
                            .map { SettingRow(it.getString("installation_id"), it.getBoolean("activity"), it.getBoolean("meal_time"), it.getBoolean("news")) }
                            .toList()
                    }
                }
            }

        fun deviceRow(memberId: Long, installationId: String): SettingRow? = settingRows(memberId).singleOrNull { it.installationId == installationId }

        fun assertDefault(node: JsonNode) {
            node.path("activity").asBoolean() shouldBe false
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

            `when`("활동/소식을 켠 회원이 조회하면") {
                then("활동/소식 켜짐이 보이고 나머지는 저장된 값대로다") {
                    val (memberId, access) = login("member-a")
                    seedSetting(memberId, activity = true, mealTime = true)

                    val node = payload(get(access))

                    node.path("activity").asBoolean() shouldBe true
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
            `when`("설정 기록이 없는 회원이 활동/소식 켜기를 보내면") {
                then("설정 기록이 생기고 활동/소식 켜짐, 나머지는 기본값이다") {
                    val (_, access) = login("member-a")

                    val node = payload(patch(access, mapOf("activity" to true)))

                    node.path("activity").asBoolean() shouldBe true
                    node.path("news").path("enabled").asBoolean() shouldBe false
                    node.path("news").path("mealTime").asBoolean() shouldBe false
                    countSettings() shouldBe 1
                }
            }

            `when`("활동/소식이 켜진 회원이 끄기를 보내면") {
                then("꺼짐으로 바뀐다") {
                    val (memberId, access) = login("member-a")
                    seedSetting(memberId, activity = true, mealTime = true)

                    payload(patch(access, mapOf("activity" to false))).path("activity").asBoolean() shouldBe false
                    payload(get(access)).path("activity").asBoolean() shouldBe false
                }
            }

            `when`("K-Bap 소식이 켜진 회원이 식사 시간 알림 끄기만 보내면") {
                then("식사 시간 알림만 꺼지고 활동/소식·동의는 그대로다") {
                    val (memberId, access) = login("member-a")
                    seedSetting(memberId, activity = true, mealTime = true)
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
                then("종류별 열린 동의가 하나씩 생기고 하위 토글인 식사 시간 알림도 켜진다") {
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

            `when`("K-Bap 소식이 켜진 회원이 끄기를 보내면") {
                then("두 종류의 열린 기록이 모두 닫히고 행은 남으며 식사 시간 알림은 응답에서 꺼짐으로 보인다") {
                    val (memberId, access) = login("member-a")
                    patch(access, enable(1, 1))
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

            `when`("식사 시간 알림을 꺼 둔 채 K-Bap 소식을 껐다가 다시 켜면") {
                then("켜기가 하위 토글을 전부 켜므로 식사 시간 알림도 켜진다") {
                    val (_, access) = login("member-a")
                    patch(access, enable(1, 1))
                    patch(access, mapOf("news" to mapOf("mealTime" to false)))
                    patch(access, disable)

                    val news = payload(patch(access, enable(1, 1))).path("news")

                    news.path("enabled").asBoolean() shouldBe true
                    news.path("mealTime").asBoolean() shouldBe true
                }
            }

            `when`("켜기와 식사 시간 알림 끄기를 한 요청에 보내면") {
                then("켜기가 먼저 반영된 뒤 mealTime 값이 덮어써 꺼진다") {
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

        given("기기별 설정 조회 — 2.1") {
            `when`("설정을 만진 적 없는 기기가 조회하면") {
                then("전부 꺼짐이고 기기 행은 생기지 않는다") {
                    val (memberId, access) = login("member-a")

                    assertDefault(payload(getDevice(access, "dev-a")))
                    settingRows(memberId).size shouldBe 0
                }
            }

            `when`("기기 A·B 가 서로 다른 값을 가진 회원이 각 기기로 조회하면") {
                then("각자 자기 기기 값을 돌려준다") {
                    val (memberId, access) = login("member-a")
                    seedDeviceSetting(memberId, "dev-a", activity = true)
                    seedDeviceSetting(memberId, "dev-b", activity = false)

                    payload(getDevice(access, "dev-a")).path("activity").asBoolean() shouldBe true
                    payload(getDevice(access, "dev-b")).path("activity").asBoolean() shouldBe false
                }
            }

            `when`("기기 식별자 헤더가 없거나 공백이거나 36자를 넘으면") {
                then("400 COMMON-002 로 거절되고 행은 생기지 않는다") {
                    val (memberId, access) = login("member-a")

                    val missing = getDevice(access, null)
                    missing.status shouldBe 400
                    missing.contentAsString shouldContain "COMMON-002"
                    getDevice(access, " ").status shouldBe 400
                    getDevice(access, "x".repeat(37)).status shouldBe 400
                    patchDevice(access, null, mapOf("activity" to true)).status shouldBe 400
                    patchDevice(access, " ", mapOf("activity" to true)).status shouldBe 400
                    patchDevice(access, "x".repeat(37), mapOf("activity" to true)).status shouldBe 400
                    settingRows(memberId).size shouldBe 0
                }
            }

            `when`("구 계약 행과 기기 행을 모두 가진 회원이 구 버전 헤더로 조회하면") {
                then("구 버전은 회원 단위 행을, 2.1 은 기기 행을 돌려준다") {
                    val (memberId, access) = login("member-a")
                    seedSetting(memberId, activity = true, mealTime = false)
                    seedDeviceSetting(memberId, "dev-a", activity = false)

                    payload(get(access, apiVersion = "1.1")).path("activity").asBoolean() shouldBe true
                    payload(get(access, apiVersion = "2.0")).path("activity").asBoolean() shouldBe true
                    payload(getDevice(access, "dev-a")).path("activity").asBoolean() shouldBe false
                }
            }

            `when`("인증 없이 조회하면") {
                then("401 로 거절된다") {
                    getDevice(null, "dev-a").status shouldBe 401
                }
            }
        }

        given("기기별 토글 수정 — 2.1") {
            `when`("기기 A·B 가 활동 켜짐인 회원이 기기 A 만 끄면") {
                then("A 는 꺼지고 B 는 그대로다") {
                    val (memberId, access) = login("member-a")
                    seedDeviceSetting(memberId, "dev-a", activity = true)
                    seedDeviceSetting(memberId, "dev-b", activity = true)

                    payload(patchDevice(access, "dev-a", mapOf("activity" to false))).path("activity").asBoolean() shouldBe false

                    payload(getDevice(access, "dev-a")).path("activity").asBoolean() shouldBe false
                    payload(getDevice(access, "dev-b")).path("activity").asBoolean() shouldBe true
                }
            }

            `when`("설정 행이 없는 기기가 활동 켜기를 보내면") {
                then("그 기기 행만 생기고 구 계약 조회는 기본값 그대로다") {
                    val (memberId, access) = login("member-a")

                    payload(patchDevice(access, "dev-a", mapOf("activity" to true))).path("activity").asBoolean() shouldBe true

                    settingRows(memberId).map { it.installationId } shouldBe listOf("dev-a")
                    assertDefault(payload(get(access, apiVersion = "1.1")))
                }
            }

            `when`("빈 본문을 보내면") {
                then("아무것도 바뀌지 않고 현재 설정을 응답한다") {
                    val (memberId, access) = login("member-a")
                    seedDeviceSetting(memberId, "dev-a", activity = true)

                    payload(patchDevice(access, "dev-a", emptyMap<String, Any>())).path("activity").asBoolean() shouldBe true
                    deviceRow(memberId, "dev-a")?.activity shouldBe true
                }
            }
        }

        given("기기별 소식 토글과 회원 동의 — 2.1") {
            fun consentOn(privacy: Int? = 2, receive: Int? = 2, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
                mapOf(
                    "news" to buildMap {
                        put("consent", true)
                        if (privacy != null) put("privacyConsentVersion", privacy)
                        if (receive != null) put("receiveConsentVersion", receive)
                        putAll(extra)
                    },
                )
            val consentOff = mapOf("news" to mapOf("consent" to false))
            fun newsOn(on: Boolean) = mapOf("news" to mapOf("enabled" to on))
            fun mealTime(on: Boolean) = mapOf("news" to mapOf("mealTime" to on))

            `when`("동의 기록이 없는 회원이 기기 A 에서 두 문구 버전과 함께 동의를 켜면") {
                then("종류별 열린 동의가 생기고 동의 받은 기기가 남으며 기기 토글값은 바뀌지 않는다") {
                    val (memberId, access) = login("member-a")

                    val news = payload(patchDevice(access, "dev-a", consentOn())).path("news")

                    news.path("enabled").asBoolean() shouldBe false
                    news.path("privacyConsent").path("version").asInt() shouldBe 2
                    news.path("receiveConsent").path("version").asInt() shouldBe 2
                    val rows = consents(memberId)
                    rows.map { it.type }.sorted() shouldBe listOf("MARKETING_PRIVACY", "MARKETING_RECEIVE")
                    rows.all { it.revokedAt == null && it.installationId == "dev-a" } shouldBe true
                    settingRows(memberId).size shouldBe 0
                }
            }

            `when`("동의가 열린 회원이 기기 A 에서 소식을 켜면") {
                then("기기 A 만 켜지고 원장은 그대로이며 기기 B 는 꺼짐이다") {
                    val (memberId, access) = login("member-a")
                    seedConsent(memberId, "MARKETING_PRIVACY", 2)
                    seedConsent(memberId, "MARKETING_RECEIVE", 2)
                    val before = consents(memberId)

                    payload(patchDevice(access, "dev-a", newsOn(true))).path("news").path("enabled").asBoolean() shouldBe true

                    deviceRow(memberId, "dev-a")?.news shouldBe true
                    consents(memberId) shouldBe before
                    payload(getDevice(access, "dev-b")).path("news").path("enabled").asBoolean() shouldBe false
                }
            }

            `when`("기기 한 대만 소식이 켜진 회원이 그 기기의 소식을 끄면") {
                then("그 기기만 꺼지고 동의는 열린 채 남는다") {
                    val (memberId, access) = login("member-a")
                    seedConsent(memberId, "MARKETING_PRIVACY", 2)
                    seedConsent(memberId, "MARKETING_RECEIVE", 2)
                    seedDeviceSetting(memberId, "dev-a", news = true)

                    payload(patchDevice(access, "dev-a", newsOn(false))).path("news").path("enabled").asBoolean() shouldBe false

                    deviceRow(memberId, "dev-a")?.news shouldBe false
                    consents(memberId).all { it.revokedAt == null } shouldBe true
                }
            }

            `when`("동의가 열리고 기기 A·B 소식이 켜진 회원이 동의를 철회하면") {
                then("열린 동의가 전부 닫히고 두 기기의 소식 토글값은 그대로다") {
                    val (memberId, access) = login("member-a")
                    seedConsent(memberId, "MARKETING_PRIVACY", 2)
                    seedConsent(memberId, "MARKETING_RECEIVE", 2)
                    seedDeviceSetting(memberId, "dev-a", news = true)
                    seedDeviceSetting(memberId, "dev-b", news = true)

                    val news = payload(patchDevice(access, "dev-a", consentOff)).path("news")

                    news.path("enabled").asBoolean() shouldBe true
                    news.path("privacyConsent").isNull shouldBe true
                    news.path("receiveConsent").isNull shouldBe true
                    consents(memberId).all { it.revokedAt != null } shouldBe true
                    deviceRow(memberId, "dev-a")?.news shouldBe true
                    deviceRow(memberId, "dev-b")?.news shouldBe true
                    payload(getDevice(access, "dev-b")).path("news").path("enabled").asBoolean() shouldBe true
                }
            }

            `when`("소식이 꺼진 기기에서 식사 시간 알림을 켜면") {
                then("NOTIFICATION-001 로 거절되고 행은 바뀌지 않는다") {
                    val (memberId, access) = login("member-a")
                    seedDeviceSetting(memberId, "dev-a", news = false)

                    val response = patchDevice(access, "dev-a", mealTime(true))

                    response.status shouldBe 400
                    response.contentAsString shouldContain "NOTIFICATION-001"
                    deviceRow(memberId, "dev-a")?.mealTime shouldBe false
                }
            }

            `when`("소식이 켜진 기기에서 동의 없이 식사 시간 알림을 켜면") {
                then("허용된다") {
                    val (memberId, access) = login("member-a")
                    seedDeviceSetting(memberId, "dev-a", news = true)

                    payload(patchDevice(access, "dev-a", mealTime(true))).path("news").path("mealTime").asBoolean() shouldBe true
                    deviceRow(memberId, "dev-a")?.mealTime shouldBe true
                }
            }

            `when`("구 버전 동의가 열린 회원이 새 버전으로 동의를 켜면") {
                then("구 버전은 닫히고 새 버전이 열리며 같은 버전 재요청은 원장을 바꾸지 않는다") {
                    val (memberId, access) = login("member-a")
                    seedConsent(memberId, "MARKETING_PRIVACY", 1)
                    seedConsent(memberId, "MARKETING_RECEIVE", 1)

                    payload(patchDevice(access, "dev-a", consentOn(2, 2)))
                    val after = consents(memberId)
                    after.size shouldBe 4
                    after.filter { it.version == 1 }.all { it.revokedAt != null } shouldBe true
                    after.filter { it.version == 2 }.all { it.revokedAt == null } shouldBe true

                    payload(patchDevice(access, "dev-a", consentOn(2, 2)))
                    consents(memberId) shouldBe after
                }
            }

            `when`("동의 켜기에 두 버전 중 하나가 빠지면") {
                then("400 COMMON-002 로 거절되고 원장·기기값은 바뀌지 않는다") {
                    val (memberId, access) = login("member-a")

                    val response = patchDevice(access, "dev-a", consentOn(privacy = 2, receive = null))

                    response.status shouldBe 400
                    response.contentAsString shouldContain "COMMON-002"
                    consents(memberId).size shouldBe 0
                    settingRows(memberId).size shouldBe 0
                }
            }

            `when`("동의 기록이 없는 회원이 동의 철회를 보내면") {
                then("아무 변화 없이 성공한다") {
                    val (memberId, access) = login("member-a")

                    payload(patchDevice(access, "dev-a", consentOff))

                    consents(memberId).size shouldBe 0
                }
            }

            `when`("동의 켜기·소식 켜기·식사 시간 켜기를 한 요청에 보내면") {
                then("동의 → 소식 → 식사 시간 순으로 반영돼 셋 다 켜진다") {
                    val (memberId, access) = login("member-a")

                    val news = payload(patchDevice(access, "dev-a", consentOn(extra = mapOf("enabled" to true, "mealTime" to true)))).path("news")

                    news.path("enabled").asBoolean() shouldBe true
                    news.path("mealTime").asBoolean() shouldBe true
                    news.path("privacyConsent").isNull shouldBe false
                    consents(memberId).size shouldBe 2
                    deviceRow(memberId, "dev-a") shouldBe SettingRow("dev-a", activity = false, mealTime = true, news = true)
                }
            }

            `when`("식사 시간 알림이 켜진 기기의 소식을 끄면") {
                then("표시값은 꺼짐이지만 식사 시간 저장값은 남는다") {
                    val (memberId, access) = login("member-a")
                    seedDeviceSetting(memberId, "dev-a", news = true, mealTime = true)

                    val news = payload(patchDevice(access, "dev-a", newsOn(false))).path("news")

                    news.path("enabled").asBoolean() shouldBe false
                    news.path("mealTime").asBoolean() shouldBe false
                    deviceRow(memberId, "dev-a")?.mealTime shouldBe true
                }
            }
        }
    }
}
