package com.kbap.api.notification

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.auth.FakeSocialTokenVerifier
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
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

        data class SettingRow(val installationId: String, val activity: Boolean, val mealTime: Boolean, val news: Boolean)

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

        fun get(accessToken: String?, installationId: String? = "dev-a", apiVersion: String = "1.1"): MockHttpServletResponse =
            mockMvc.get("/api/notifications/settings") {
                header("X-API-Version", apiVersion)
                if (accessToken != null) header("Authorization", "Bearer $accessToken")
                if (installationId != null) header("X-Installation-Id", installationId)
            }.andReturn().response

        fun patch(accessToken: String?, body: Any, installationId: String? = "dev-a", apiVersion: String = "1.1"): MockHttpServletResponse =
            mockMvc.patch("/api/notifications/settings") {
                header("X-API-Version", apiVersion)
                if (accessToken != null) header("Authorization", "Bearer $accessToken")
                if (installationId != null) header("X-Installation-Id", installationId)
                contentType = MediaType.APPLICATION_JSON
                content = if (body is String) body else objectMapper.writeValueAsString(body)
            }.andReturn().response

        fun payload(response: MockHttpServletResponse): JsonNode {
            response.status shouldBe 200
            return objectMapper.readTree(response.contentAsString).path("payload")
        }

        fun seedSetting(memberId: Long, installationId: String, activity: Boolean = false, mealTime: Boolean = false, news: Boolean = false) {
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

        fun row(memberId: Long, installationId: String): SettingRow? = settingRows(memberId).singleOrNull { it.installationId == installationId }

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
            `when`("설정을 만진 적 없는 기기가 조회하면") {
                then("전부 꺼짐이고 기기 행은 생기지 않는다") {
                    val (memberId, access) = login("member-a")

                    assertDefault(payload(get(access)))
                    settingRows(memberId).size shouldBe 0
                }
            }

            `when`("기기 A·B 가 서로 다른 값을 가진 회원이 각 기기로 조회하면") {
                then("각자 자기 기기 값을 돌려준다") {
                    val (memberId, access) = login("member-a")
                    seedSetting(memberId, "dev-a", activity = true)
                    seedSetting(memberId, "dev-b", activity = false)

                    payload(get(access, "dev-a")).path("activity").asBoolean() shouldBe true
                    payload(get(access, "dev-b")).path("activity").asBoolean() shouldBe false
                }
            }

            `when`("동의 두 건이 열린 회원의 소식 켜진 기기가 조회하면") {
                then("소식 켜짐과 동의 각각의 버전·시각, 식사 시간 알림 값이 보인다") {
                    val (memberId, access) = login("member-a")
                    seedSetting(memberId, "dev-a", news = true, mealTime = false)
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

            `when`("한쪽 동의만 열린 회원이 조회하면") {
                then("열린 동의만 채워지고 소식 사용 여부는 기기 저장값 그대로다") {
                    val (memberId, access) = login("member-a")
                    seedSetting(memberId, "dev-a", news = true, mealTime = true)
                    seedConsent(memberId, "MARKETING_PRIVACY", 1, revoked = true)
                    seedConsent(memberId, "MARKETING_RECEIVE", 1)

                    val news = payload(get(access)).path("news")

                    news.path("enabled").asBoolean() shouldBe true
                    news.path("mealTime").asBoolean() shouldBe true
                    news.path("privacyConsent").isNull shouldBe true
                    news.path("receiveConsent").path("version").asInt() shouldBe 1
                }
            }

            `when`("기기 식별자 헤더가 없거나 공백이거나 36자를 넘으면") {
                then("400 COMMON-002 로 거절되고 행은 생기지 않는다") {
                    val (memberId, access) = login("member-a")

                    val missing = get(access, installationId = null)
                    missing.status shouldBe 400
                    missing.contentAsString shouldContain "COMMON-002"
                    get(access, " ").status shouldBe 400
                    get(access, "x".repeat(37)).status shouldBe 400
                    patch(access, mapOf("activity" to true), installationId = null).status shouldBe 400
                    patch(access, mapOf("activity" to true), installationId = " ").status shouldBe 400
                    patch(access, mapOf("activity" to true), installationId = "x".repeat(37)).status shouldBe 400
                    settingRows(memberId).size shouldBe 0
                }
            }

            `when`("인증 없이 조회하면") {
                then("401 로 거절된다") {
                    get(null).status shouldBe 401
                }
            }

            `when`("X-API-Version 1.0 으로 조회하면") {
                then("1.0 부터 동작한다") {
                    val (_, access) = login("member-a")

                    assertDefault(payload(get(access, apiVersion = "1.0")))
                }
            }
        }

        given("토글 수정") {
            `when`("설정 행이 없는 기기가 활동 켜기를 보내면") {
                then("그 기기 행만 생기고 활동 켜짐, 나머지는 기본값이다") {
                    val (memberId, access) = login("member-a")

                    val node = payload(patch(access, mapOf("activity" to true)))

                    node.path("activity").asBoolean() shouldBe true
                    node.path("news").path("enabled").asBoolean() shouldBe false
                    settingRows(memberId) shouldBe listOf(SettingRow("dev-a", activity = true, mealTime = false, news = false))
                }
            }

            `when`("기기 A·B 가 활동 켜짐인 회원이 기기 A 만 끄면") {
                then("A 는 꺼지고 B 는 그대로다") {
                    val (memberId, access) = login("member-a")
                    seedSetting(memberId, "dev-a", activity = true)
                    seedSetting(memberId, "dev-b", activity = true)

                    payload(patch(access, mapOf("activity" to false), "dev-a")).path("activity").asBoolean() shouldBe false

                    payload(get(access, "dev-a")).path("activity").asBoolean() shouldBe false
                    payload(get(access, "dev-b")).path("activity").asBoolean() shouldBe true
                }
            }

            `when`("빈 본문을 보내면") {
                then("아무것도 바뀌지 않고 현재 설정을 응답한다") {
                    val (memberId, access) = login("member-a")
                    seedSetting(memberId, "dev-a", activity = true)

                    payload(patch(access, emptyMap<String, Any>())).path("activity").asBoolean() shouldBe true
                    row(memberId, "dev-a")?.activity shouldBe true
                }
            }

            `when`("인증 없이 수정하면") {
                then("401 로 거절된다") {
                    patch(null, mapOf("activity" to true)).status shouldBe 401
                }
            }
        }

        given("소식 토글과 회원 동의") {
            `when`("동의 기록이 없는 회원이 기기 A 에서 두 문구 버전과 함께 동의를 켜면") {
                then("종류별 열린 동의가 생기고 동의 받은 기기가 남으며 기기 토글값은 바뀌지 않는다") {
                    val (memberId, access) = login("member-a")

                    val news = payload(patch(access, consentOn())).path("news")

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

                    payload(patch(access, newsOn(true))).path("news").path("enabled").asBoolean() shouldBe true

                    row(memberId, "dev-a")?.news shouldBe true
                    consents(memberId) shouldBe before
                    payload(get(access, "dev-b")).path("news").path("enabled").asBoolean() shouldBe false
                }
            }

            `when`("기기 한 대만 소식이 켜진 회원이 그 기기의 소식을 끄면") {
                then("그 기기만 꺼지고 동의는 열린 채 남는다") {
                    val (memberId, access) = login("member-a")
                    seedConsent(memberId, "MARKETING_PRIVACY", 2)
                    seedConsent(memberId, "MARKETING_RECEIVE", 2)
                    seedSetting(memberId, "dev-a", news = true)

                    payload(patch(access, newsOn(false))).path("news").path("enabled").asBoolean() shouldBe false

                    row(memberId, "dev-a")?.news shouldBe false
                    consents(memberId).all { it.revokedAt == null } shouldBe true
                }
            }

            `when`("동의가 열리고 기기 A·B 소식이 켜진 회원이 동의를 철회하면") {
                then("열린 동의가 전부 닫히고 두 기기의 소식 토글값은 그대로다") {
                    val (memberId, access) = login("member-a")
                    seedConsent(memberId, "MARKETING_PRIVACY", 2)
                    seedConsent(memberId, "MARKETING_RECEIVE", 2)
                    seedSetting(memberId, "dev-a", news = true)
                    seedSetting(memberId, "dev-b", news = true)

                    val news = payload(patch(access, consentOff)).path("news")

                    news.path("enabled").asBoolean() shouldBe true
                    news.path("privacyConsent").isNull shouldBe true
                    news.path("receiveConsent").isNull shouldBe true
                    consents(memberId).all { it.revokedAt != null } shouldBe true
                    row(memberId, "dev-a")?.news shouldBe true
                    row(memberId, "dev-b")?.news shouldBe true
                    payload(get(access, "dev-b")).path("news").path("enabled").asBoolean() shouldBe true
                }
            }

            `when`("소식이 꺼진 기기에서 식사 시간 알림을 켜면") {
                then("NOTIFICATION-001 로 거절되고 행은 바뀌지 않는다") {
                    val (memberId, access) = login("member-a")
                    seedSetting(memberId, "dev-a", news = false)

                    val response = patch(access, mealTime(true))

                    response.status shouldBe 400
                    response.contentAsString shouldContain "NOTIFICATION-001"
                    row(memberId, "dev-a")?.mealTime shouldBe false
                }
            }

            `when`("소식이 켜진 기기에서 동의 없이 식사 시간 알림을 켜면") {
                then("허용된다") {
                    val (memberId, access) = login("member-a")
                    seedSetting(memberId, "dev-a", news = true)

                    payload(patch(access, mealTime(true))).path("news").path("mealTime").asBoolean() shouldBe true
                    row(memberId, "dev-a")?.mealTime shouldBe true
                }
            }

            `when`("소식이 꺼진 기기에서 식사 시간 알림 끄기를 보내면") {
                then("끄기는 허용되고 값이 저장된다") {
                    val (memberId, access) = login("member-a")
                    seedSetting(memberId, "dev-a", news = false, mealTime = true)

                    payload(patch(access, mealTime(false))).path("news").path("mealTime").asBoolean() shouldBe false
                    row(memberId, "dev-a")?.mealTime shouldBe false
                }
            }

            `when`("구 버전 동의가 열린 회원이 새 버전으로 동의를 켜면") {
                then("구 버전은 닫히고 새 버전이 열리며 같은 버전 재요청은 원장을 바꾸지 않는다") {
                    val (memberId, access) = login("member-a")
                    seedConsent(memberId, "MARKETING_PRIVACY", 1)
                    seedConsent(memberId, "MARKETING_RECEIVE", 1)

                    payload(patch(access, consentOn(2, 2)))
                    val after = consents(memberId)
                    after.size shouldBe 4
                    after.filter { it.version == 1 }.all { it.revokedAt != null } shouldBe true
                    after.filter { it.version == 2 }.all { it.revokedAt == null } shouldBe true

                    payload(patch(access, consentOn(2, 2)))
                    consents(memberId) shouldBe after
                }
            }

            `when`("광고성 정보 수신 동의 버전만 올려 동의를 켜면") {
                then("수신 동의는 이전 기록이 닫히고 새 버전 기록이 생기며 개인정보 동의는 그대로다") {
                    val (memberId, access) = login("member-a")
                    payload(patch(access, consentOn(1, 1)))
                    val first = consents(memberId)

                    payload(patch(access, consentOn(1, 2)))

                    val after = consents(memberId)
                    after.size shouldBe 3
                    after.single { it.type == "MARKETING_PRIVACY" }.id shouldBe first.single { it.type == "MARKETING_PRIVACY" }.id
                    after.single { it.type == "MARKETING_RECEIVE" && it.version == 1 }.revokedAt shouldNotBe null
                    after.single { it.type == "MARKETING_RECEIVE" && it.version == 2 }.revokedAt shouldBe null
                }
            }

            `when`("동의 켜기에 두 버전 중 하나가 빠지면") {
                then("400 COMMON-002 로 거절되고 원장·기기값은 바뀌지 않는다") {
                    val (memberId, access) = login("member-a")

                    val response = patch(access, consentOn(privacy = 2, receive = null))

                    response.status shouldBe 400
                    response.contentAsString shouldContain "COMMON-002"
                    consents(memberId).size shouldBe 0
                    settingRows(memberId).size shouldBe 0
                }
            }

            `when`("동의 버전이 양의 정수가 아니면") {
                then("400 COMMON-002 로 거절된다") {
                    val (_, access) = login("member-a")

                    patch(access, consentOn(0, 1)).status shouldBe 400
                    patch(access, consentOn(1, 65536)).status shouldBe 400
                }
            }

            `when`("동의 기록이 없는 회원이 동의 철회를 보내면") {
                then("아무 변화 없이 성공한다") {
                    val (memberId, access) = login("member-a")

                    payload(patch(access, consentOff))

                    consents(memberId).size shouldBe 0
                }
            }

            `when`("동의 켜기 요청에 클라이언트 동의 시각을 실어 보내면") {
                then("그 값은 무시되고 서버 시각이 기록된다") {
                    val (memberId, access) = login("member-a")

                    payload(patch(access, consentOn(extra = mapOf("grantedAt" to "2000-01-01T00:00:00"))))

                    consents(memberId).all { !it.grantedAt.startsWith("2000-01-01") } shouldBe true
                }
            }

            `when`("동의 켜기·소식 켜기·식사 시간 켜기를 한 요청에 보내면") {
                then("동의 → 소식 → 식사 시간 순으로 반영돼 셋 다 켜진다") {
                    val (memberId, access) = login("member-a")

                    val news = payload(patch(access, consentOn(extra = mapOf("enabled" to true, "mealTime" to true)))).path("news")

                    news.path("enabled").asBoolean() shouldBe true
                    news.path("mealTime").asBoolean() shouldBe true
                    news.path("privacyConsent").isNull shouldBe false
                    consents(memberId).size shouldBe 2
                    row(memberId, "dev-a") shouldBe SettingRow("dev-a", activity = false, mealTime = true, news = true)
                }
            }

            `when`("식사 시간 알림이 켜진 기기의 소식을 끄면") {
                then("표시값은 꺼짐이지만 식사 시간 저장값은 남는다") {
                    val (memberId, access) = login("member-a")
                    seedSetting(memberId, "dev-a", news = true, mealTime = true)

                    val news = payload(patch(access, newsOn(false))).path("news")

                    news.path("enabled").asBoolean() shouldBe false
                    news.path("mealTime").asBoolean() shouldBe false
                    row(memberId, "dev-a")?.mealTime shouldBe true
                }
            }
        }
    }
}
