package com.kbap.api.feedback

import com.kbap.api.IntegrationTest
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class FeedbackControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        fun clear(): Unit =
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.execute("DELETE FROM feedback_reply")
                    it.execute("DELETE FROM feedback")
                    it.execute("DELETE FROM uploaded_image")
                }
            }

        beforeContainer { clear() }
        afterSpec { clear() }

        fun seedMember(memberId: Long): Unit =
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
                    ps.setString(2, "feedback-$memberId")
                    ps.setString(3, "문의회원$memberId")
                    ps.executeUpdate()
                }
            }

        fun token(memberId: Long): String {
            seedMember(memberId)
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun seedAdminAccount(id: Long): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO admin_account (id, admin_id, admin_pwd, status, created_at, updated_at)
                    VALUES (?, ?, 'x', 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE status = 'ACTIVE'
                    """,
                ).use { ps -> ps.setLong(1, id); ps.setString(2, "feedback-admin-$id"); ps.executeUpdate() }
            }

        fun adminToken(): String {
            seedAdminAccount(1L)
            return tokenIssuer.issueAccessToken(1L, MemberRole.ADMIN)
        }

        fun seedUpload(path: String, memberId: Long? = null, installationId: String? = null): Unit =
            dataSource.connection.use { c ->
                memberId?.let { seedMember(it) }
                c.prepareStatement(
                    """
                    INSERT INTO uploaded_image (member_id, installation_id, object_path, content_type,
                                                size_bytes, status, created_at, updated_at)
                    VALUES (?, ?, ?, 'image/webp', 1024, 'ACTIVE', NOW(6), NOW(6))
                    """,
                ).use { ps ->
                    if (memberId == null) ps.setNull(1, java.sql.Types.BIGINT) else ps.setLong(1, memberId)
                    ps.setString(2, installationId)
                    ps.setString(3, path)
                    ps.executeUpdate()
                }
            }

        fun submit(
            token: String? = null,
            installationId: String? = "feedback-install-0001",
            body: Map<String, Any?> = mapOf("content" to "홈에서 스캔 버튼이 두 번 눌려요"),
        ): ResultActionsDsl =
            mockMvc.post("/api/feedbacks") {
                token?.let { header("Authorization", "Bearer $it") }
                installationId?.let { header("X-Installation-Id", it) }
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(body)
            }

        fun mine(token: String? = null, installationId: String? = "feedback-install-0001"): ResultActionsDsl =
            mockMvc.get("/api/feedbacks/me") {
                token?.let { header("Authorization", "Bearer $it") }
                installationId?.let { header("X-Installation-Id", it) }
            }

        fun payloadOf(result: ResultActionsDsl): JsonNode =
            mapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8)).path("payload")

        given("문의 제출") {
            `when`("게스트가 설치 ID 헤더와 함께 보내면") {
                then("201 로 접수되고 상태는 OPEN 이다") {
                    val payload = payloadOf(
                        submit(installationId = "guest-install-0001").andExpect { status { isCreated() } },
                    )
                    payload.path("status").asText() shouldBe "OPEN"
                    (payload.path("id").asLong() > 0) shouldBe true
                }
            }

            `when`("회원이 보내면") {
                then("201 로 접수되고 내 문의에서 보인다") {
                    submit(token = token(9101L), installationId = "member-install-0001")
                        .andExpect { status { isCreated() } }

                    payloadOf(mine(token = token(9101L), installationId = "other-install"))
                        .path("items").size() shouldBe 1
                }
            }

            `when`("설치 ID 헤더가 없으면") {
                then("400 COMMON-002 로 거절한다") {
                    submit(installationId = null).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }

            `when`("본문이 공백뿐이면") {
                then("400 FEEDBACK-001 로 거절한다") {
                    submit(body = mapOf("content" to "   ")).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FEEDBACK-001") }
                    }
                }
            }

            `when`("사진을 4장 보내면") {
                then("400 FEEDBACK-002 로 거절한다") {
                    val paths = (1..4).map { "images/feedback/$it.webp" }
                    submit(body = mapOf("content" to "사진 많음", "imagePaths" to paths)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FEEDBACK-002") }
                    }
                }
            }

            `when`("남이 올린 사진을 참조하면") {
                then("400 FEEDBACK-002 로 거절한다") {
                    seedUpload("images/feedback/other.webp", memberId = 9110L)

                    submit(
                        token = token(9111L),
                        body = mapOf("content" to "남의 사진", "imagePaths" to listOf("images/feedback/other.webp")),
                    ).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FEEDBACK-002") }
                    }
                }
            }

            `when`("게스트가 같은 기기로 올린 사진을 붙이면") {
                then("201 로 접수된다 — 기기 소유를 인정한다") {
                    seedUpload("images/feedback/guest.webp", installationId = "guest-photo-install")

                    submit(
                        installationId = "guest-photo-install",
                        body = mapOf("content" to "게스트 사진", "imagePaths" to listOf("images/feedback/guest.webp")),
                    ).andExpect { status { isCreated() } }
                }
            }

            `when`("기기 문의가 하루 한도를 넘기면") {
                then("429 FEEDBACK-003 으로 거절한다") {
                    val installation = "quota-install-0001"
                    repeat(FeedbackService.DAILY_LIMIT) {
                        submit(installationId = installation, body = mapOf("content" to "문의 $it"))
                            .andExpect { status { isCreated() } }
                    }

                    submit(installationId = installation, body = mapOf("content" to "한도 초과")).andExpect {
                        status { isTooManyRequests() }
                        jsonPath("$.code") { value("FEEDBACK-003") }
                    }
                }
            }
        }

        given("내 문의 조회") {
            `when`("게스트로 보낸 뒤 같은 기기에서 로그인해 조회하면") {
                then("그 문의가 그대로 보인다 — 설치 ID 매칭") {
                    submit(installationId = "keep-install-0001", body = mapOf("content" to "게스트 문의"))
                        .andExpect { status { isCreated() } }

                    val items = payloadOf(mine(token = token(9120L), installationId = "keep-install-0001")).path("items")
                    items.size() shouldBe 1
                    items[0].path("content").asText() shouldBe "게스트 문의"
                }
            }

            `when`("어드민이 답변을 달면") {
                then("내 문의 목록에 답변이 함께 내려온다") {
                    val id = payloadOf(
                        submit(installationId = "reply-install-0001", body = mapOf("content" to "답변 받을 문의")),
                    ).path("id").asLong()

                    mockMvc.post("/api/admin/feedbacks/$id/replies") {
                        header("Authorization", "Bearer ${adminToken()}")
                        contentType = MediaType.APPLICATION_JSON
                        content = mapper.writeValueAsString(mapOf("content" to "확인했어요"))
                    }.andExpect { status { isCreated() } }

                    val item = payloadOf(mine(installationId = "reply-install-0001")).path("items")[0]
                    item.path("status").asText() shouldBe "ANSWERED"
                    item.path("replies").size() shouldBe 1
                    item.path("replies")[0].path("content").asText() shouldBe "확인했어요"
                }
            }
        }
    }
}
