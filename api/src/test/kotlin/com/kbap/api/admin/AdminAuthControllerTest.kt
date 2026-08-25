package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.admin.AdminTestTokens.adminHeaders
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.admin.AdminAccountJpaRepository
import com.kbap.common.domain.admin.AdminAuditLogJpaRepository
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminAuthControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var adminAccountRepository: AdminAccountJpaRepository

    @Autowired
    private lateinit var auditLogRepository: AdminAuditLogJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val objectMapper = jacksonObjectMapper()

    init {
        fun login(id: String, password: String): MvcResult =
            mockMvc.post("/api/admin/auth/login") {
                header("X-API-Version", AdminTestTokens.API_VERSION)
                contentType = MediaType.APPLICATION_JSON
                content = """{"id":"$id","password":"$password"}"""
            }.andReturn()

        fun payload(result: MvcResult): Map<String, Any?> =
            objectMapper.readValue<Map<String, Any?>>(result.response.contentAsString)

        fun code(result: MvcResult): String? = payload(result)["code"] as String?

        @Suppress("UNCHECKED_CAST")
        fun tokens(result: MvcResult): Map<String, Any?> = payload(result)["payload"] as Map<String, Any?>

        beforeContainer {
            adminAccountRepository.deleteAll()
            auditLogRepository.deleteAll()
        }

        given("관리자 로그인") {
            `when`("올바른 자격으로 로그인하면") {
                then("액세스 토큰과 만료 초를 받고 마지막 로그인 시각·감사 이력이 남는다") {
                    val account = AdminTestTokens.seedAdminAccount(adminAccountRepository, "ops", "secret1")

                    val result = login("ops", "secret1")

                    result.response.status shouldBe 200
                    val t = tokens(result)
                    (t["accessToken"] as String).shouldNotBeBlank()
                    t.containsKey("refreshToken") shouldBe false
                    t["expiresIn"] shouldBe 28800
                    adminAccountRepository.findById(account.id).get().lastLoginAt.shouldNotBeNull()
                    auditLogRepository.findAll().single().action shouldBe AdminAuditAction.ADMIN_LOGIN
                }
            }

            `when`("비밀번호가 틀리면") {
                then("401 AUTH-009 — 몇 번을 틀려도 잠기지 않는다") {
                    AdminTestTokens.seedAdminAccount(adminAccountRepository, "ops", "secret1")

                    repeat(6) { login("ops", "wrong").response.status shouldBe 401 }
                    code(login("ops", "wrong")) shouldBe "AUTH-009"
                    login("ops", "secret1").response.status shouldBe 200
                }
            }

            `when`("없는 아이디면") {
                then("401 AUTH-009") {
                    code(login("ghost", "secret1")) shouldBe "AUTH-009"
                }
            }
        }

        given("관리자 자격과 회원 자격의 분리") {
            `when`("관리자 액세스 토큰으로 회원 전용 API 를 호출하면") {
                then("401 AUTH-003") {
                    val result = mockMvc.get("/api/members/me/profile") {
                        adminHeaders(AdminTestTokens.adminAccessToken(tokenIssuer))
                    }.andReturn()

                    result.response.status shouldBe 401
                    code(result) shouldBe "AUTH-003"
                }
            }
        }
    }
}
