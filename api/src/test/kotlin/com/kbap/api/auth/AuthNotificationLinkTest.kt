package com.kbap.api.auth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import javax.sql.DataSource

@IntegrationTest
class AuthNotificationLinkTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var verifier: FakeSocialTokenVerifier

    @Autowired
    private lateinit var accountDeleter: FakeSocialAccountDeleter

    init {
        val objectMapper = jacksonObjectMapper()

        data class Session(val memberId: Long, val accessToken: String, val refreshToken: String)

        data class Consent(val memberId: Long?, val type: String, val version: Int, val grantedAt: String, val revokedAt: String?)

        fun memberIdOf(providerUid: String): Long =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT id FROM member WHERE provider_uid = ?").use { ps ->
                    ps.setString(1, providerUid)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else error("회원 없음: $providerUid") }
                }
            }

        fun loginResponse(sub: String, installationId: String?, apiVersion: String): MockHttpServletResponse =
            mockMvc.post("/api/auth/login") {
                header("X-API-Version", apiVersion)
                if (installationId != null) header("X-Installation-Id", installationId)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("idToken" to sub))
            }.andReturn().response

        fun login(sub: String, installationId: String?, apiVersion: String = "1.1"): Session {
            val response = loginResponse(sub, installationId, apiVersion)
            response.status shouldBe 200
            val payload = objectMapper.readTree(response.contentAsString).path("payload")
            return Session(memberIdOf(sub), payload.path("accessToken").asText(), payload.path("refreshToken").asText())
        }

        fun logout(refreshToken: String, installationId: String?, apiVersion: String = "1.1"): MockHttpServletResponse =
            mockMvc.post("/api/auth/logout") {
                header("X-API-Version", apiVersion)
                if (installationId != null) header("X-Installation-Id", installationId)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("refreshToken" to refreshToken))
            }.andReturn().response

        fun withdraw(accessToken: String, apiVersion: String = "1.1"): MockHttpServletResponse =
            mockMvc.patch("/api/auth/withdraw") {
                header("X-API-Version", apiVersion)
                header("Authorization", "Bearer $accessToken")
            }.andReturn().response

        fun registerToken(installationId: String, accessToken: String) {
            mockMvc.put("/api/notifications/tokens") {
                header("X-API-Version", "1.1")
                header("X-Installation-Id", installationId)
                header("Authorization", "Bearer $accessToken")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("token" to "ExponentPushToken[$installationId]", "platform" to "ios", "lang" to "en"))
            }.andReturn().response.status shouldBe 200
        }

        fun registerUnlinkedDevice(installationId: String) {
            val seed = login("member-seed", null)
            registerToken(installationId, seed.accessToken)
            logout(seed.refreshToken, installationId).status shouldBe 200
        }

        fun deviceMemberId(installationId: String): Long? =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT member_id FROM notification_device WHERE installation_id = ?").use { ps ->
                    ps.setString(1, installationId)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) error("기기 없음: $installationId")
                        rs.getObject(1)?.let { (it as Number).toLong() }
                    }
                }
            }

        fun countDevices(): Int =
            dataSource.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT COUNT(*) FROM notification_device").use { rs -> rs.next(); rs.getInt(1) }
                }
            }

        fun consentsOf(whereClause: String, bind: (java.sql.PreparedStatement) -> Unit): List<Consent> =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT member_id, consent_type, consent_version, granted_at, revoked_at FROM notification_consent WHERE $whereClause ORDER BY id",
                ).use { ps ->
                    bind(ps)
                    ps.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs else null }
                            .map {
                                Consent(
                                    it.getObject("member_id")?.let { id -> (id as Number).toLong() },
                                    it.getString("consent_type"),
                                    it.getInt("consent_version"),
                                    it.getString("granted_at"),
                                    it.getString("revoked_at"),
                                )
                            }
                            .toList()
                    }
                }
            }

        fun openConsentsOfMember(memberId: Long): List<Consent> =
            consentsOf("member_id = ? AND revoked_at IS NULL") { it.setLong(1, memberId) }

        fun countConsents(): Int =
            dataSource.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT COUNT(*) FROM notification_consent").use { rs -> rs.next(); rs.getInt(1) }
                }
            }

        fun insertOpenGuestConsent(installationId: String, version: Int, type: String = "MARKETING_RECEIVE") {
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "INSERT INTO notification_consent (installation_id, consent_type, consent_version, granted_at, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, NOW(6), 'ACTIVE', NOW(6), NOW(6))",
                ).use { ps ->
                    ps.setString(1, installationId)
                    ps.setString(2, type)
                    ps.setInt(3, version)
                    ps.executeUpdate()
                }
            }
        }

        fun insertOpenMemberConsent(memberId: Long, version: Int, type: String = "MARKETING_RECEIVE") {
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "INSERT INTO notification_consent (member_id, consent_type, consent_version, granted_at, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, NOW(6), 'ACTIVE', NOW(6), NOW(6))",
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.setString(2, type)
                    ps.setInt(3, version)
                    ps.executeUpdate()
                }
            }
        }

        fun memberEntityStatus(memberId: Long): String? =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT status FROM member WHERE id = ?").use { ps ->
                    ps.setLong(1, memberId)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }

        beforeContainer {
            TestTables.clearAll(dataSource)
            verifier.reset()
            accountDeleter.reset()
        }

        given("등록된 기기에서 로그인") {
            `when`("설치 식별자로 남은 게스트 동의 행이 있는 기기에서 로그인하면") {
                then("기기만 회원에 연결되고 동의 원장은 한 행도 바뀌지 않는다") {
                    registerUnlinkedDevice("dev-1")
                    insertOpenGuestConsent("dev-1", 1, "MARKETING_PRIVACY")
                    insertOpenGuestConsent("dev-1", 1, "MARKETING_RECEIVE")
                    val before = consentsOf("1 = 1") { }

                    val session = login("member-a", "dev-1")

                    deviceMemberId("dev-1") shouldBe session.memberId
                    consentsOf("1 = 1") { } shouldBe before
                    openConsentsOfMember(session.memberId).size shouldBe 0
                }
            }

            `when`("서버가 모르는 기기 식별자로 로그인하면") {
                then("로그인은 성공하고 기기·동의 기록은 생기지 않는다") {
                    login("member-a", "unknown-device")

                    countDevices() shouldBe 0
                    countConsents() shouldBe 0
                }
            }

            `when`("기기 식별자 헤더 없이 로그인하면") {
                then("로그인은 성공하고 등록된 기기의 연결은 바뀌지 않는다") {
                    registerUnlinkedDevice("dev-1")

                    login("member-a", null)

                    deviceMemberId("dev-1").shouldBeNull()
                }
            }

            `when`("같은 기기로 두 번 로그인하면") {
                then("상태가 첫 번째와 같다") {
                    registerUnlinkedDevice("dev-1")
                    insertOpenGuestConsent("dev-1", 1)
                    val session = login("member-a", "dev-1")
                    val afterFirst = consentsOf("1 = 1") { }

                    login("member-a", "dev-1")

                    deviceMemberId("dev-1") shouldBe session.memberId
                    consentsOf("1 = 1") { } shouldBe afterFirst
                }
            }
        }

        given("다른 회원이 연결된 기기에서 로그인") {
            `when`("회원 A 가 연결된 기기에서 회원 B 가 로그인하면") {
                then("기기 연결은 B 로 바뀐다") {
                    val a = login("member-a", "dev-1")
                    registerToken("dev-1", a.accessToken)
                    deviceMemberId("dev-1") shouldBe a.memberId

                    val b = login("member-b", "dev-1")

                    deviceMemberId("dev-1") shouldBe b.memberId
                }
            }
        }

        given("회원 기기에서 로그아웃") {
            `when`("기기 식별자 헤더와 함께 로그아웃하면") {
                then("기기의 회원 연결만 비워지고 동의 원장은 한 행도 바뀌지 않는다") {
                    val session = login("member-a", "dev-1")
                    registerToken("dev-1", session.accessToken)
                    insertOpenMemberConsent(session.memberId, 2)
                    val before = consentsOf("1 = 1") { }

                    logout(session.refreshToken, "dev-1").status shouldBe 200

                    deviceMemberId("dev-1").shouldBeNull()
                    consentsOf("1 = 1") { } shouldBe before
                }
            }

            `when`("기기 식별자 헤더 없이 로그아웃하면") {
                then("로그아웃은 성공하고 연결은 유지된다") {
                    val session = login("member-a", "dev-1")
                    registerToken("dev-1", session.accessToken)

                    logout(session.refreshToken, null).status shouldBe 200

                    deviceMemberId("dev-1") shouldBe session.memberId
                }
            }

            `when`("두 번 로그아웃하면") {
                then("두 번째도 성공하고 상태는 같다") {
                    val session = login("member-a", "dev-1")
                    registerToken("dev-1", session.accessToken)

                    logout(session.refreshToken, "dev-1").status shouldBe 200
                    logout(session.refreshToken, "dev-1").status shouldBe 200

                    deviceMemberId("dev-1").shouldBeNull()
                }
            }
        }

        given("회원 탈퇴") {
            `when`("기기 두 대가 연결되고 열린 동의가 있는 회원이 탈퇴하면") {
                then("두 기기 모두 연결이 비워지고 열린 동의는 전부 닫히며 기록은 남는다") {
                    val session = login("member-a", "dev-1")
                    registerToken("dev-1", session.accessToken)
                    registerToken("dev-2", session.accessToken)
                    insertOpenMemberConsent(session.memberId, 1)
                    insertOpenMemberConsent(session.memberId, 2)
                    val total = countConsents()

                    withdraw(session.accessToken).status shouldBe 200

                    memberEntityStatus(session.memberId) shouldBe "DELETED"
                    deviceMemberId("dev-1").shouldBeNull()
                    deviceMemberId("dev-2").shouldBeNull()
                    openConsentsOfMember(session.memberId).size shouldBe 0
                    countConsents() shouldBe total
                    countDevices() shouldBe 2
                }
            }
        }

        fun patchDeviceSettings(accessToken: String, installationId: String, body: Map<String, Any?>): MockHttpServletResponse =
            mockMvc.patch("/api/notifications/settings") {
                header("X-API-Version", "2.1")
                header("X-Installation-Id", installationId)
                header("Authorization", "Bearer $accessToken")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }.andReturn().response

        fun deviceActivity(accessToken: String, installationId: String): Boolean {
            val response = mockMvc.get("/api/notifications/settings") {
                header("X-API-Version", "2.1")
                header("X-Installation-Id", installationId)
                header("Authorization", "Bearer $accessToken")
            }.andReturn().response
            response.status shouldBe 200
            return objectMapper.readTree(response.contentAsString).path("payload").path("activity").asBoolean()
        }

        fun insertLegacySetting(memberId: Long) {
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "INSERT INTO notification_setting (member_id, activity, meal_time, news, status, created_at, updated_at) " +
                        "VALUES (?, TRUE, FALSE, FALSE, 'ACTIVE', NOW(6), NOW(6))",
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.executeUpdate()
                }
            }
        }

        fun settingStatuses(memberId: Long): Map<String?, String> =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT installation_id, status FROM notification_setting WHERE member_id = ?").use { ps ->
                    ps.setLong(1, memberId)
                    ps.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs else null }
                            .associate { it.getString("installation_id") to it.getString("status") }
                    }
                }
            }

        given("기기별 설정과 로그아웃") {
            `when`("기기 A 설정을 바꾼 회원이 로그아웃 후 같은 기기로 재로그인하면") {
                then("설정이 그대로다") {
                    val first = login("member-a", "dev-1")
                    registerToken("dev-1", first.accessToken)
                    patchDeviceSettings(first.accessToken, "dev-1", mapOf("activity" to true)).status shouldBe 200

                    logout(first.refreshToken, "dev-1").status shouldBe 200
                    deviceMemberId("dev-1").shouldBeNull()
                    settingStatuses(first.memberId) shouldBe mapOf("dev-1" to "ACTIVE")

                    val second = login("member-a", "dev-1")
                    deviceActivity(second.accessToken, "dev-1") shouldBe true
                }
            }
        }

        given("기기별 설정과 탈퇴") {
            `when`("기기 두 대의 설정과 구 계약 행, 열린 동의를 가진 회원이 탈퇴하면") {
                then("기기 행은 소프트 삭제되고 구 계약 행은 그대로이며 기기 연결·동의는 닫힌다") {
                    val session = login("member-a", "dev-1")
                    registerToken("dev-1", session.accessToken)
                    registerToken("dev-2", session.accessToken)
                    patchDeviceSettings(session.accessToken, "dev-1", mapOf("activity" to true)).status shouldBe 200
                    patchDeviceSettings(session.accessToken, "dev-2", mapOf("activity" to true)).status shouldBe 200
                    insertLegacySetting(session.memberId)
                    insertOpenMemberConsent(session.memberId, 2)

                    withdraw(session.accessToken).status shouldBe 200

                    settingStatuses(session.memberId) shouldBe mapOf(null to "ACTIVE", "dev-1" to "DELETED", "dev-2" to "DELETED")
                    deviceMemberId("dev-1").shouldBeNull()
                    deviceMemberId("dev-2").shouldBeNull()
                    openConsentsOfMember(session.memberId).size shouldBe 0
                }
            }
        }

        given("로그인 → 등록 → 로그아웃 → 재로그인") {
            `when`("실기기 한 대로 연속 수행하면") {
                then("기기 기록은 시종 1건이고 연결 회원이 단계에 맞게 바뀐다") {
                    val first = login("member-a", "dev-1")
                    registerToken("dev-1", first.accessToken)
                    deviceMemberId("dev-1") shouldBe first.memberId

                    logout(first.refreshToken, "dev-1")
                    deviceMemberId("dev-1").shouldBeNull()

                    val second = login("member-a", "dev-1")
                    deviceMemberId("dev-1") shouldBe second.memberId

                    countDevices() shouldBe 1
                }
            }
        }

        given("1.0 인증 API 무영향") {
            `when`("1.0 헤더와 기기 식별자로 로그인하면") {
                then("로그인은 성공하지만 기기는 연결되지 않고 동의 원장도 그대로다") {
                    registerUnlinkedDevice("dev-1")
                    insertOpenGuestConsent("dev-1", 1)
                    val before = consentsOf("1 = 1") { }

                    login("member-a", "dev-1", apiVersion = "1.0")

                    deviceMemberId("dev-1").shouldBeNull()
                    consentsOf("1 = 1") { } shouldBe before
                }
            }

            `when`("1.1 로그인으로 연결된 기기에서 1.0 으로 로그아웃하면") {
                then("로그아웃은 성공하지만 연결은 유지된다") {
                    val session = login("member-a", "dev-1")
                    registerToken("dev-1", session.accessToken)

                    logout(session.refreshToken, "dev-1", apiVersion = "1.0").status shouldBe 200

                    deviceMemberId("dev-1") shouldBe session.memberId
                }
            }

            `when`("1.1 로그인으로 연결된 회원이 1.0 으로 탈퇴하면") {
                then("탈퇴는 성공하지만 기기 연결과 열린 동의는 그대로다") {
                    val session = login("member-a", "dev-1")
                    registerToken("dev-1", session.accessToken)
                    insertOpenMemberConsent(session.memberId, 1)

                    withdraw(session.accessToken, apiVersion = "1.0").status shouldBe 200

                    memberEntityStatus(session.memberId) shouldBe "DELETED"
                    deviceMemberId("dev-1") shouldBe session.memberId
                    openConsentsOfMember(session.memberId).size shouldBe 1
                }
            }
        }
    }
}
