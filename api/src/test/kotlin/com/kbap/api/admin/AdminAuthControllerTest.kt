package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.admin.AdminTestTokens.adminHeaders
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.core.testsupport.RedisContainerConfig
import com.kbap.common.domain.admin.AdminAccountJpaRepository
import com.kbap.common.domain.admin.AdminAuditLogJpaRepository
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.RefreshTokenStore
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class, RedisContainerConfig::class)
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

    @Autowired
    private lateinit var refreshTokenStore: RefreshTokenStore

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    private val objectMapper = jacksonObjectMapper()

    init {
        fun login(id: String, password: String): MvcResult =
            mockMvc.post("/api/admin/auth/login") {
                header("X-API-Version", AdminTestTokens.API_VERSION)
                contentType = MediaType.APPLICATION_JSON
                content = """{"id":"$id","password":"$password"}"""
            }.andReturn()

        fun refresh(refreshToken: String): MvcResult =
            mockMvc.post("/api/admin/auth/refresh") {
                header("X-API-Version", AdminTestTokens.API_VERSION)
                contentType = MediaType.APPLICATION_JSON
                content = """{"refreshToken":"$refreshToken"}"""
            }.andReturn()

        fun payload(result: MvcResult): Map<String, Any?> =
            objectMapper.readValue<Map<String, Any?>>(result.response.contentAsString)

        fun code(result: MvcResult): String? = payload(result)["code"] as String?

        @Suppress("UNCHECKED_CAST")
        fun tokens(result: MvcResult): Map<String, Any?> = payload(result)["payload"] as Map<String, Any?>

        beforeContainer {
            adminAccountRepository.deleteAll()
            auditLogRepository.deleteAll()
            redisTemplate.keys("admin:login-fail:*").forEach { redisTemplate.delete(it) }
        }

        given("관리자 로그인") {
            `when`("올바른 자격으로 로그인하면") {
                then("액세스·갱신 토큰과 만료 초를 받고 감사 이력이 남는다") {
                    AdminTestTokens.seedAdminAccount(adminAccountRepository, "ops", "secret1")

                    val result = login("ops", "secret1")

                    result.response.status shouldBe 200
                    val t = tokens(result)
                    (t["accessToken"] as String).shouldNotBeBlank()
                    (t["refreshToken"] as String).shouldNotBeBlank()
                    t["expiresIn"] shouldBe 3600
                    auditLogRepository.findAll().single().action shouldBe AdminAuditAction.ADMIN_LOGIN
                }
            }

            `when`("비밀번호가 틀리면") {
                then("401 AUTH-009") {
                    AdminTestTokens.seedAdminAccount(adminAccountRepository, "ops", "secret1")

                    val result = login("ops", "wrong")

                    result.response.status shouldBe 401
                    code(result) shouldBe "AUTH-009"
                }
            }

            `when`("5회 연속 실패하면") {
                then("정답을 넣어도 403 AUTH-010 으로 잠긴다") {
                    AdminTestTokens.seedAdminAccount(adminAccountRepository, "locked", "secret1")
                    repeat(5) { login("locked", "wrong").response.status shouldBe 401 }

                    val result = login("locked", "secret1")

                    result.response.status shouldBe 403
                    code(result) shouldBe "AUTH-010"
                }
            }

            `when`("4회 실패 후 성공하면") {
                then("카운터가 초기화되어 이후 실패가 다시 1부터 센다") {
                    AdminTestTokens.seedAdminAccount(adminAccountRepository, "reset", "secret1")
                    repeat(4) { login("reset", "wrong") }
                    login("reset", "secret1").response.status shouldBe 200

                    login("reset", "wrong").response.status shouldBe 401
                    login("reset", "secret1").response.status shouldBe 200
                }
            }
        }

        given("관리자 갱신 토큰") {
            `when`("갱신하면") {
                then("회전되어 이전 갱신 토큰은 재사용할 수 없다") {
                    AdminTestTokens.seedAdminAccount(adminAccountRepository, "ops", "secret1")
                    val first = tokens(login("ops", "secret1"))["refreshToken"] as String

                    val rotated = refresh(first)
                    rotated.response.status shouldBe 200
                    (tokens(rotated)["refreshToken"] as String).shouldNotBeBlank()

                    val reused = refresh(first)
                    reused.response.status shouldBe 401
                    code(reused) shouldBe "AUTH-005"
                }
            }

            `when`("회원용(USER) 갱신 토큰으로 관리자 갱신을 요청하면") {
                then("401 AUTH-005") {
                    val userRefresh = tokenIssuer.issueRefreshToken(1L, MemberRole.USER)
                    refreshTokenStore.save(userRefresh.jti, 1L, Duration.ofMinutes(5))

                    val result = refresh(userRefresh.token)

                    result.response.status shouldBe 401
                    code(result) shouldBe "AUTH-005"
                }
            }

            `when`("관리자 갱신 토큰으로 회원 갱신 API 를 호출하면") {
                then("401 AUTH-005") {
                    AdminTestTokens.seedAdminAccount(adminAccountRepository, "ops", "secret1")
                    val adminRefresh = tokens(login("ops", "secret1"))["refreshToken"] as String

                    val result = mockMvc.post("/api/auth/refresh") {
                        header("X-API-Version", AdminTestTokens.API_VERSION)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"refreshToken":"$adminRefresh"}"""
                    }.andReturn()

                    result.response.status shouldBe 401
                    code(result) shouldBe "AUTH-005"
                }
            }

            `when`("로그아웃 후 갱신하면") {
                then("401") {
                    AdminTestTokens.seedAdminAccount(adminAccountRepository, "ops", "secret1")
                    val t = tokens(login("ops", "secret1"))
                    val refreshToken = t["refreshToken"] as String

                    mockMvc.post("/api/admin/auth/logout") {
                        adminHeaders(t["accessToken"] as String)
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"refreshToken":"$refreshToken"}"""
                    }.andExpect { status { isOk() } }

                    refresh(refreshToken).response.status shouldBe 401
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
