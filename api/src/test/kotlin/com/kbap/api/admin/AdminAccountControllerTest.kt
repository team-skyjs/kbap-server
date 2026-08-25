package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.admin.AdminTestTokens.adminHeaders
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.core.testsupport.RedisContainerConfig
import com.kbap.common.domain.admin.AdminAccountJpaRepository
import com.kbap.common.domain.admin.AdminAuditLogJpaRepository
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class, RedisContainerConfig::class)
class AdminAccountControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var adminAccountRepository: AdminAccountJpaRepository

    @Autowired
    private lateinit var auditLogRepository: AdminAuditLogJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    private val objectMapper = jacksonObjectMapper()

    init {
        fun token(adminId: Long) = AdminTestTokens.adminAccessToken(tokenIssuer, adminId)
        fun get(adminId: Long): MvcResult = mockMvc.get("/api/admin/accounts") { adminHeaders(token(adminId)) }.andReturn()
        fun post(adminId: Long, body: String): MvcResult =
            mockMvc.post("/api/admin/accounts") {
                adminHeaders(token(adminId))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
        fun changePassword(adminId: Long, body: String): MvcResult =
            mockMvc.patch("/api/admin/accounts/me/password") {
                adminHeaders(token(adminId))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
        fun login(id: String, password: String): MvcResult =
            mockMvc.post("/api/admin/auth/login") {
                header("X-API-Version", AdminTestTokens.API_VERSION)
                contentType = MediaType.APPLICATION_JSON
                content = """{"id":"$id","password":"$password"}"""
            }.andReturn()

        fun json(r: MvcResult): Map<String, Any?> = objectMapper.readValue(r.response.contentAsString)

        @Suppress("UNCHECKED_CAST")
        fun payload(r: MvcResult) = json(r)["payload"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        fun items(r: MvcResult) = payload(r)["items"] as List<Map<String, Any?>>

        beforeContainer {
            dataSource.connection.use { c ->
                c.createStatement().use { st ->
                    listOf("admin_audit_log", "admin_account").forEach { st.execute("DELETE FROM $it") }
                }
            }
        }

        given("관리자 계정 관리") {
            `when`("계정을 만들고 로그인하면") {
                then("목록에 마지막 로그인 시각이 찍히고 중복 아이디는 409") {
                    val me = AdminTestTokens.seedAdminAccount(adminAccountRepository, "root", "rootpass1")

                    val created = post(me.id, """{"loginId":"ops2","password":"secret12"}""")
                    created.response.status shouldBe 200
                    payload(created)["loginId"] shouldBe "ops2"
                    payload(created)["lastLoginAt"].shouldBeNull()

                    val dup = post(me.id, """{"loginId":"ops2","password":"secret12"}""")
                    dup.response.status shouldBe 409
                    json(dup)["code"] shouldBe "AUTH-011"

                    post(me.id, """{"loginId":"ab","password":"secret12"}""").response.status shouldBe 400
                    post(me.id, """{"loginId":"ops3","password":"short"}""").response.status shouldBe 400

                    login("ops2", "secret12").response.status shouldBe 200
                    val list = items(get(me.id))
                    list.map { it["loginId"] } shouldContainExactly listOf("root", "ops2")
                    list.last()["lastLoginAt"].shouldNotBeNull()
                    auditLogRepository.findAll().map { it.action } shouldContainExactly listOf(AdminAuditAction.ADMIN_ACCOUNT_CREATE, AdminAuditAction.ADMIN_LOGIN)
                }
            }

            `when`("내 비밀번호를 바꾸면") {
                then("현재 비밀번호가 틀리면 400, 맞으면 새 비밀번호로만 로그인된다") {
                    val me = AdminTestTokens.seedAdminAccount(adminAccountRepository, "root", "rootpass1")

                    val wrong = changePassword(me.id, """{"currentPassword":"nope","newPassword":"newpass12"}""")
                    wrong.response.status shouldBe 400
                    json(wrong)["code"] shouldBe "AUTH-012"

                    val ok = changePassword(me.id, """{"currentPassword":"rootpass1","newPassword":"newpass12"}""")
                    ok.response.status shouldBe 200
                    payload(ok)["passwordChangedAt"].shouldNotBeNull()
                    login("root", "rootpass1").response.status shouldBe 401
                    login("root", "newpass12").response.status shouldBe 200
                }
            }

            `when`("계정을 삭제하면") {
                then("본인은 400, 타인은 소프트 삭제되고 목록에서 사라진다") {
                    val me = AdminTestTokens.seedAdminAccount(adminAccountRepository, "root", "rootpass1")
                    val other = AdminTestTokens.seedAdminAccount(adminAccountRepository, "ops2", "secret12")

                    val self = mockMvc.delete("/api/admin/accounts/${me.id}") { adminHeaders(token(me.id)) }.andReturn()
                    self.response.status shouldBe 400
                    json(self)["code"] shouldBe "AUTH-013"

                    mockMvc.delete("/api/admin/accounts/${other.id}") { adminHeaders(token(me.id)) }.andReturn().response.status shouldBe 200
                    items(get(me.id)).map { it["loginId"] } shouldContainExactly listOf("root")
                    login("ops2", "secret12").response.status shouldBe 401

                    val missing = mockMvc.delete("/api/admin/accounts/${other.id}") { adminHeaders(token(me.id)) }.andReturn()
                    missing.response.status shouldBe 404
                    json(missing)["code"] shouldBe "AUTH-014"
                }
            }
        }
    }
}
