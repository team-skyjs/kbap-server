package com.kbap.api.block

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
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
                    INSERT INTO member (id, provider, provider_uid, nickname, member_status,
                                        onboarding_completed, status, created_at, updated_at)
                    VALUES (?, 'GOOGLE', ?, ?, 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))
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

        given("차단 목록 — GET /api/v1/members/me/blocks") {
            fun blockedList(token: String?): JsonNode =
                mapper.readTree(
                    mockMvc.get("/api/v1/members/me/blocks") {
                        token?.let { header("Authorization", "Bearer $it") }
                    }.andReturn().response.getContentAsString(Charsets.UTF_8),
                ).path("payload")

            fun updateMember(memberId: Long, column: String, value: String): Unit =
                dataSource.connection.use { c ->
                    c.createStatement().use { it.execute("UPDATE member SET $column = '$value' WHERE id = $memberId") }
                }

            `when`("여러 회원을 차단한 상태로 조회하면") {
                then("전원을 닉네임·프로필 이미지와 함께 반환한다") {
                    val token = accessToken(9121L)
                    seedMember(9122L, nickname = "차단대상122")
                    seedMember(9123L, nickname = "차단대상123")
                    dataSource.connection.use { c ->
                        c.createStatement().use {
                            it.execute("UPDATE member SET profile_image_url = 'images/profile/p122.jpg' WHERE id = 9122")
                        }
                    }
                    block(token, 9122L).andExpect { status { isOk() } }
                    block(token, 9123L).andExpect { status { isOk() } }

                    val items = blockedList(token)
                    items.size() shouldBe 2
                    val byId = items.associateBy { it.path("memberId").asLong() }
                    byId.keys shouldBe setOf(9122L, 9123L)
                    byId.getValue(9122L).path("nickname").asText() shouldBe "차단대상122"
                    byId.getValue(9122L).path("profileImageUrl").asText() shouldBe "https://cdn.test/images/profile/p122.jpg"
                    byId.getValue(9123L).path("profileImageUrl").isNull.shouldBeTrue()
                }
            }
            `when`("차단한 회원이 닉네임을 바꾼 뒤 조회하면") {
                then("최신 닉네임이 보인다") {
                    val token = accessToken(9124L)
                    seedMember(9125L, nickname = "옛닉네임")
                    block(token, 9125L).andExpect { status { isOk() } }
                    updateMember(9125L, "nickname", "새닉네임")

                    blockedList(token).first().path("nickname").asText() shouldBe "새닉네임"
                }
            }
            `when`("차단을 해제한 회원이 있으면") {
                then("목록에서 빠진다") {
                    val token = accessToken(9126L)
                    seedMember(9127L)
                    block(token, 9127L).andExpect { status { isOk() } }
                    mockMvc.delete("/api/v1/members/me/blocks/9127") {
                        header("Authorization", "Bearer $token")
                    }.andExpect { status { isOk() } }

                    blockedList(token).size() shouldBe 0
                }
            }
            `when`("차단한 회원이 탈퇴하면") {
                then("목록에서 빠진다") {
                    val token = accessToken(9128L)
                    seedMember(9129L)
                    block(token, 9129L).andExpect { status { isOk() } }
                    updateMember(9129L, "status", "DELETED")

                    blockedList(token).size() shouldBe 0
                }
            }
            `when`("아무도 차단하지 않은 회원이 조회하면") {
                then("빈 목록을 준다") {
                    blockedList(accessToken(9130L)).size() shouldBe 0
                }
            }
            `when`("토큰 없이 조회하면") {
                then("401 을 반환한다") {
                    mockMvc.get("/api/v1/members/me/blocks")
                        .andExpect { status { isUnauthorized() } }
                }
            }
        }
    }
}
