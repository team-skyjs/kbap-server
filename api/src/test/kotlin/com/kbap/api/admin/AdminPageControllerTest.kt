package com.kbap.api.admin

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.admin.AdminAccountJpaRepository
import com.kbap.common.domain.admin.model.AdminAccount
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.http.Cookie
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminPageControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var adminAccountJpaRepository: AdminAccountJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    init {
        val encoder = BCryptPasswordEncoder()

        fun clearAccounts() = adminAccountJpaRepository.deleteAll()

        fun seedAccount(loginId: String = "admin", rawPassword: String = "changeit"): AdminAccount =
            adminAccountJpaRepository.save(AdminAccount(loginId = loginId, password = encoder.encode(rawPassword)!!))

        fun adminCookie(accountId: Long): Cookie =
            Cookie(AdminPageAuthInterceptor.COOKIE_NAME, tokenIssuer.issueAccessToken(accountId, MemberRole.ADMIN))

        fun login(loginId: String, password: String) =
            mockMvc.post("/admin/login") {
                param("id", loginId)
                param("password", password)
            }

        beforeContainer { clearAccounts() }

        given("관리자 로그인") {
            `when`("올바른 자격 증명으로 로그인하면") {
                then("ADMIN JWT 세션 쿠키를 심고 /admin 으로 리다이렉트한다") {
                    seedAccount()

                    val result = login("admin", "changeit")
                        .andExpect {
                            status { is3xxRedirection() }
                            redirectedUrl("/admin")
                            cookie {
                                exists(AdminPageAuthInterceptor.COOKIE_NAME)
                                httpOnly(AdminPageAuthInterceptor.COOKIE_NAME, true)
                                secure(AdminPageAuthInterceptor.COOKIE_NAME, true)
                                path(AdminPageAuthInterceptor.COOKIE_NAME, "/admin")
                                maxAge(AdminPageAuthInterceptor.COOKIE_NAME, -1)
                            }
                        }.andReturn()

                    result.response.getHeader("Set-Cookie")!! shouldContain "SameSite=Strict"
                }
            }

            `when`("비밀번호가 틀리면") {
                then("쿠키 없이 로그인 화면에 오류 문구를 보여준다") {
                    seedAccount()

                    login("admin", "wrong-password").andExpect {
                        status { isOk() }
                        view { name("admin/login") }
                        model { attributeExists("error") }
                    }
                }
            }

            `when`("존재하지 않는 계정이면") {
                then("계정 존재 여부를 구분할 수 없는 동일한 오류 문구를 보여준다") {
                    login("ghost", "changeit").andExpect {
                        status { isOk() }
                        view { name("admin/login") }
                        model { attributeExists("error") }
                    }
                }
            }
        }

        given("관리자 페이지 접근 제어") {
            `when`("미인증으로 홈에 접근하면") {
                then("로그인 화면으로 리다이렉트한다") {
                    mockMvc.get("/admin").andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/login")
                    }
                }
            }

            `when`("미인증으로 로그인 화면에 접근하면") {
                then("로그인 폼을 보여준다") {
                    mockMvc.get("/admin/login").andExpect {
                        status { isOk() }
                        view { name("admin/login") }
                    }
                }
            }

            `when`("인증 상태로 로그인 화면에 접근하면") {
                then("홈으로 리다이렉트한다") {
                    val account = seedAccount()

                    mockMvc.get("/admin/login") { cookie(adminCookie(account.id)) }.andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin")
                    }
                }
            }

            `when`("인증 상태로 홈에 접근하면") {
                then("홈 화면을 보여준다") {
                    val account = seedAccount()

                    mockMvc.get("/admin") { cookie(adminCookie(account.id)) }.andExpect {
                        status { isOk() }
                        view { name("admin/home") }
                    }
                }
            }

            `when`("로그아웃하면") {
                then("쿠키를 만료시키고 로그인 화면으로 리다이렉트한다") {
                    val account = seedAccount()

                    mockMvc.post("/admin/logout") { cookie(adminCookie(account.id)) }.andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/login")
                        cookie {
                            maxAge(AdminPageAuthInterceptor.COOKIE_NAME, 0)
                            path(AdminPageAuthInterceptor.COOKIE_NAME, "/admin")
                        }
                    }
                }
            }
        }

        given("admin_account 스키마 제약") {
            `when`("같은 loginId 로 두 번 저장하면") {
                then("unique 제약 위반으로 실패한다 — Flyway 스키마 검증") {
                    seedAccount(loginId = "dup")

                    shouldThrow<DataIntegrityViolationException> {
                        adminAccountJpaRepository.saveAndFlush(
                            AdminAccount(loginId = "dup", password = encoder.encode("other")!!),
                        )
                    }
                }
            }
        }

        given("관리자 토큰의 회원 API 교차 사용") {
            `when`("ADMIN 역할 토큰을 Bearer 헤더로 회원 API 에 보내면") {
                then("회원 신원으로 해석되지 않고 401 로 거절한다") {
                    val account = seedAccount()
                    val adminToken = tokenIssuer.issueAccessToken(account.id, MemberRole.ADMIN)

                    mockMvc.get("/api/v1/members/me/profile") {
                        header("Authorization", "Bearer $adminToken")
                    }.andExpect {
                        status { isUnauthorized() }
                    }
                }
            }
        }
    }
}
