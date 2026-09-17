package com.kbap.api.admin

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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class AdminFeedbackControllerTest : BehaviorSpec() {
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
                }
            }

        beforeContainer { clear() }
        afterSpec { clear() }

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
            seedAdminAccount(7L)
            return tokenIssuer.issueAccessToken(7L, MemberRole.ADMIN)
        }

        fun submit(installationId: String, text: String, deviceInfo: Map<String, String>? = null): Long {
            val body = buildMap<String, Any?> {
                put("content", text)
                deviceInfo?.let { put("deviceInfo", it) }
            }
            val response = mockMvc.post("/api/feedbacks") {
                header("X-Installation-Id", installationId)
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(body)
            }.andReturn().response.getContentAsString(Charsets.UTF_8)
            return mapper.readTree(response).path("payload").path("id").asLong()
        }

        fun list(query: String = "", token: String? = adminToken()): ResultActionsDsl =
            mockMvc.get("/api/admin/feedbacks$query") { token?.let { header("Authorization", "Bearer $it") } }

        fun detail(id: Long): ResultActionsDsl =
            mockMvc.get("/api/admin/feedbacks/$id") { header("Authorization", "Bearer ${adminToken()}") }

        fun reply(id: Long, text: String): ResultActionsDsl =
            mockMvc.post("/api/admin/feedbacks/$id/replies") {
                header("Authorization", "Bearer ${adminToken()}")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("content" to text))
            }

        fun changeStatus(id: Long, status: String): ResultActionsDsl =
            mockMvc.patch("/api/admin/feedbacks/$id/status") {
                header("Authorization", "Bearer ${adminToken()}")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("status" to status))
            }

        fun payloadOf(result: ResultActionsDsl): JsonNode =
            mapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8)).path("payload")

        given("어드민 문의 목록") {
            `when`("status 를 생략하면") {
                then("OPEN 만 최신순으로 내려주고 페이지 정보가 함께 온다") {
                    submit("admin-list-001", "첫 문의")
                    val closed = submit("admin-list-002", "닫을 문의")
                    changeStatus(closed, "CLOSED").andExpect { status { isOk() } }

                    val payload = payloadOf(list())
                    payload.path("items").size() shouldBe 1
                    payload.path("items")[0].path("status").asText() shouldBe "OPEN"
                    payload.path("totalCount").asLong() shouldBe 1L
                    payload.path("totalPages").asInt() shouldBe 1
                    payload.path("page").asInt() shouldBe 0
                }
            }

            `when`("status=ALL 이면") {
                then("상태와 무관하게 전부 내려준다") {
                    submit("admin-all-001", "열린 문의")
                    val closed = submit("admin-all-002", "닫힌 문의")
                    changeStatus(closed, "CLOSED").andExpect { status { isOk() } }

                    payloadOf(list("?status=ALL")).path("items").size() shouldBe 2
                }
            }

            `when`("게스트 문의를 보면") {
                then("reporterKey 가 설치 ID 기준이고 닉네임은 null 이다") {
                    submit("admin-key-001", "게스트가 보낸 문의", mapOf("os" to "ios", "appVersion" to "1.0.3"))

                    val item = payloadOf(list()).path("items")[0]
                    item.path("reporterKey").asText() shouldBe "inst:admin-key-001"
                    item.path("memberId").isNull shouldBe true
                    item.path("memberNickname").isNull shouldBe true
                    item.path("app").path("os").asText() shouldBe "ios"
                    item.path("app").path("appVersion").asText() shouldBe "1.0.3"
                }
            }

            `when`("어드민 토큰 없이 조회하면") {
                then("거절한다") {
                    list(token = null).andExpect { status { isUnauthorized() } }
                }
            }
        }

        given("어드민 문의 상세·답변") {
            `when`("상세를 조회하면") {
                then("기기 정보 전체와 서버 메타가 함께 온다") {
                    val id = submit("detail-001", "상세 볼 문의", mapOf("os" to "android", "deviceModel" to "Pixel 8"))

                    val payload = payloadOf(detail(id))
                    payload.path("installationId").asText() shouldBe "detail-001"
                    payload.path("deviceInfo").path("deviceModel").asText() shouldBe "Pixel 8"
                    payload.path("serverMeta").path("receivedAt").isMissingNode shouldBe false
                    payload.path("replies").size() shouldBe 0
                }
            }

            `when`("없는 문의를 조회하면") {
                then("404 FEEDBACK-004 로 거절한다") {
                    detail(999_999L).andExpect {
                        status { isNotFound() }
                        jsonPath("$.code") { value("FEEDBACK-004") }
                    }
                }
            }

            `when`("답변을 달면") {
                then("201 로 답변과 바뀐 상태를 함께 준다") {
                    val id = submit("reply-001", "답변할 문의")

                    val payload = payloadOf(
                        reply(id, "곧 고칠게요").andExpect { status { isCreated() } },
                    )
                    payload.path("status").asText() shouldBe "ANSWERED"
                    payload.path("reply").path("content").asText() shouldBe "곧 고칠게요"
                    payload.path("reply").path("adminAccountId").asLong() shouldBe 7L
                }
            }

            `when`("종료된 문의에 답변하면") {
                then("409 FEEDBACK-005 로 거절한다") {
                    val id = submit("closed-001", "닫힌 문의")
                    changeStatus(id, "CLOSED").andExpect { status { isOk() } }

                    reply(id, "늦은 답변").andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("FEEDBACK-005") }
                    }
                }
            }
        }
    }
}
