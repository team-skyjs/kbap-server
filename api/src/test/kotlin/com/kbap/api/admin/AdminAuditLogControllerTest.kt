package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.admin.AdminTestTokens.adminHeaders
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.admin.AdminAccountJpaRepository
import com.kbap.common.domain.admin.AdminAuditLogJpaRepository
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.admin.model.AdminAuditLog
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminAuditLogControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var auditLogRepository: AdminAuditLogJpaRepository

    @Autowired
    private lateinit var adminAccountRepository: AdminAccountJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val objectMapper = jacksonObjectMapper()

    init {
        @Suppress("UNCHECKED_CAST")
        fun query(params: String, token: String = AdminTestTokens.adminAccessToken(tokenIssuer)): Map<String, Any?> {
            val body = mockMvc.get("/api/admin/audit-logs$params") { adminHeaders(token) }
                .andExpect { status { isOk() } }
                .andReturn().response.contentAsString
            return objectMapper.readValue<Map<String, Any?>>(body)["payload"] as Map<String, Any?>
        }

        @Suppress("UNCHECKED_CAST")
        fun items(payload: Map<String, Any?>) = payload["items"] as List<Map<String, Any?>>

        beforeContainer {
            auditLogRepository.deleteAll()
            adminAccountRepository.deleteAll()
        }

        given("감사 이력 조회") {
            `when`("대상·조작자·조작 종류로 거르면") {
                then("해당 행만 최신순으로 오고 로그인 아이디가 붙는다") {
                    val ops = AdminTestTokens.seedAdminAccount(adminAccountRepository, "ops")
                    val other = AdminTestTokens.seedAdminAccount(adminAccountRepository, "other")
                    auditLogRepository.saveAll(
                        listOf(
                            AdminAuditLog(ops.id, AdminAuditAction.FOOD_UPDATE, AdminAuditTargetType.FOOD, 10L, mapOf("d" to "a"), mapOf("d" to "b")),
                            AdminAuditLog(other.id, AdminAuditAction.FOOD_DELETE, AdminAuditTargetType.FOOD, 10L, null, null, "삭제"),
                            AdminAuditLog(ops.id, AdminAuditAction.MEMBER_STATUS, AdminAuditTargetType.MEMBER, 5L, null, mapOf("memberStatus" to "SUSPENDED")),
                        ),
                    )

                    val byTarget = query("?targetType=FOOD&targetId=10")
                    items(byTarget).size shouldBe 2
                    items(byTarget).first()["action"] shouldBe "FOOD_DELETE"
                    items(byTarget).first()["adminLoginId"] shouldBe "other"
                    byTarget["totalCount"] shouldBe 2

                    items(query("?adminAccountId=${ops.id}")).size shouldBe 2
                    items(query("?action=MEMBER_STATUS")).single()["after"] shouldBe mapOf("memberStatus" to "SUSPENDED")
                    items(query("?targetType=FOOD&targetId=10&action=FOOD_UPDATE")).single()["before"] shouldBe mapOf("d" to "a")
                }
            }

            `when`("페이지 크기를 주면") {
                then("페이지 메타가 계산된다") {
                    val ops = AdminTestTokens.seedAdminAccount(adminAccountRepository, "ops")
                    auditLogRepository.saveAll(
                        (1..5).map { AdminAuditLog(ops.id, AdminAuditAction.FOOD_UPDATE, AdminAuditTargetType.FOOD, it.toLong(), null, null) },
                    )

                    val page = query("?size=2&page=2")

                    items(page).size shouldBe 2
                    page["page"] shouldBe 2
                    page["totalPages"] shouldBe 3
                    page["totalCount"] shouldBe 5
                }
            }

            `when`("회원 토큰으로 조회하면") {
                then("403 AUTH-008") {
                    mockMvc.get("/api/admin/audit-logs") {
                        adminHeaders(AdminTestTokens.userAccessToken(tokenIssuer, 1L))
                    }.andExpect { status { isForbidden() } }
                }
            }
        }
    }
}
