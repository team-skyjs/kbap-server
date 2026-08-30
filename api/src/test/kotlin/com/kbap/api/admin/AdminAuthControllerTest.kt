package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.domain.admin.AdminAccountJpaRepository
import com.kbap.common.domain.admin.model.AdminAccount
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenParser
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post

@IntegrationTest
class AdminAuthControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var adminAccountJpaRepository: AdminAccountJpaRepository

    @Autowired
    private lateinit var tokenParser: TokenParser

    init {
        val path = "/api/admin/auth/login"
        val mapper = jacksonObjectMapper()
        val passwordEncoder = BCryptPasswordEncoder()
        val loginId = "kb387-admin"
        val rawPassword = "kb387-secret"

        fun saveAccount() {
            if (adminAccountJpaRepository.findByLoginId(loginId) == null) {
                adminAccountJpaRepository.save(
                    AdminAccount(loginId = loginId, password = passwordEncoder.encode(rawPassword)!!),
                )
            }
        }

        fun login(body: Map<String, String?>): ResultActionsDsl =
            mockMvc.post(path) {
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(body)
            }

        beforeContainer { saveAccount() }

        given("어드민 JSON 로그인 API") {
            `when`("올바른 자격으로 로그인하면") {
                then("액세스 토큰 없이도 ADMIN 토큰을 발급한다") {
                    val result = login(mapOf("id" to loginId, "password" to rawPassword))
                        .andExpect {
                            status { isOk() }
                            jsonPath("$.success") { value(true) }
                            jsonPath("$.payload.accessToken") { exists() }
                        }.andReturn()

                    val accessToken = mapper.readTree(result.response.contentAsString)
                        .path("payload").path("accessToken").asText()
                    tokenParser.parseAccessToken(accessToken).role shouldBe MemberRole.ADMIN
                }
            }

            `when`("비밀번호가 틀리면") {
                then("401(AUTH-009) 과 표시용 메시지를 내려준다") {
                    login(mapOf("id" to loginId, "password" to "wrong-password")).andExpect {
                        status { isUnauthorized() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.code") { value("AUTH-009") }
                        jsonPath("$.message") { exists() }
                    }
                }
            }

            `when`("존재하지 않는 아이디면") {
                then("같은 401(AUTH-009) 로 응답한다") {
                    login(mapOf("id" to "no-such-admin", "password" to rawPassword)).andExpect {
                        status { isUnauthorized() }
                        jsonPath("$.code") { value("AUTH-009") }
                    }
                }
            }

            `when`("id 나 password 가 비어 있으면") {
                then("400(COMMON-002) 검증 실패로 응답한다") {
                    login(mapOf("id" to loginId, "password" to null)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }
        }
    }
}
