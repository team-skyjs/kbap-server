package com.kbap.api.block

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class MemberBlockControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        fun seedMember(memberId: Long, nickname: String = "차단러$memberId"): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO member (id, provider, provider_uid, nickname, profile, member_status,
                                        onboarding_completed, status, created_at, updated_at)
                    VALUES (?, 'GOOGLE', ?, ?, '{}', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE nickname = VALUES(nickname)
                    """,
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.setString(2, "block-controller-test-$memberId")
                    ps.setString(3, nickname)
                    ps.executeUpdate()
                }
            }

        fun accessToken(memberId: Long): String {
            seedMember(memberId)
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun block(token: String?, targetMemberId: Long?): ResultActionsDsl =
            mockMvc.post("/api/v1/members/me/blocks") {
                token?.let { header("Authorization", "Bearer $it") }
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(
                    if (targetMemberId == null) emptyMap() else mapOf("memberId" to targetMemberId),
                )
            }

        given("차단 등록 — POST /api/v1/members/me/blocks") {
            `when`("다른 회원을 차단하면") {
                then("200 success 를 반환한다") {
                    val token = accessToken(9101L)
                    seedMember(9102L)
                    block(token, 9102L).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }
                }
            }
            `when`("이미 차단한 회원을 다시 차단하면") {
                then("멱등하게 200 을 반환한다") {
                    val token = accessToken(9103L)
                    seedMember(9104L)
                    block(token, 9104L).andExpect { status { isOk() } }
                    block(token, 9104L).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }
                }
            }
            `when`("자기 자신을 차단하면") {
                then("400 BLOCK-001 을 반환한다") {
                    val token = accessToken(9105L)
                    block(token, 9105L).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("BLOCK-001") }
                    }
                }
            }
            `when`("존재하지 않는 회원을 차단하면") {
                then("404 BLOCK-002 를 반환한다") {
                    val token = accessToken(9106L)
                    block(token, 999_999_999L).andExpect {
                        status { isNotFound() }
                        jsonPath("$.code") { value("BLOCK-002") }
                    }
                }
            }
            `when`("memberId 없이 요청하면") {
                then("400 COMMON-002 를 반환한다") {
                    val token = accessToken(9107L)
                    block(token, null).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }
            `when`("토큰 없이 요청하면") {
                then("401 을 반환한다") {
                    seedMember(9108L)
                    block(null, 9108L).andExpect { status { isUnauthorized() } }
                }
            }
        }

        given("차단 해제 — DELETE /api/v1/members/me/blocks/{memberId}") {
            fun unblock(token: String?, targetMemberId: Long): ResultActionsDsl =
                mockMvc.delete("/api/v1/members/me/blocks/$targetMemberId") {
                    token?.let { header("Authorization", "Bearer $it") }
                }

            `when`("차단 중인 회원을 해제하면") {
                then("200 success 를 반환한다") {
                    val token = accessToken(9111L)
                    seedMember(9112L)
                    block(token, 9112L).andExpect { status { isOk() } }
                    unblock(token, 9112L).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }
                }
            }
            `when`("차단한 적 없는 회원을 해제하면") {
                then("멱등하게 200 을 반환한다") {
                    val token = accessToken(9113L)
                    unblock(token, 999_999_999L).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }
                }
            }
            `when`("토큰 없이 요청하면") {
                then("401 을 반환한다") {
                    seedMember(9114L)
                    unblock(null, 9114L).andExpect { status { isUnauthorized() } }
                }
            }
        }
    }
}
