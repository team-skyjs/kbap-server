package com.kbap.api.community

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeSorted
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class CommentReadControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        fun execute(sql: String, vararg params: Any?): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(sql).use { ps ->
                    params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
                    ps.executeUpdate()
                }
            }

        fun seedMember(memberId: Long, nickname: String = "댓글독자$memberId"): Unit =
            execute(
                """
                INSERT INTO member (id, provider, provider_uid, nickname, profile, member_status,
                                    onboarding_completed, status, created_at, updated_at)
                VALUES (?, 'GOOGLE', ?, ?, '{"countryCode":"KR"}', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))
                ON DUPLICATE KEY UPDATE id = id
                """,
                memberId,
                "comment-read-$memberId",
                nickname,
            )

        fun withdrawMember(memberId: Long): Unit =
            execute("UPDATE member SET status = 'DELETED' WHERE id = ?", memberId)

        fun accessToken(memberId: Long): String {
            seedMember(memberId)
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun seedPost(postId: Long, memberId: Long): Unit {
            seedMember(memberId)
            execute(
                """
                INSERT INTO community_post (id, member_id, content, status, created_at, updated_at)
                VALUES (?, ?, '댓글 조회 테스트 글', 'ACTIVE', NOW(6), NOW(6))
                ON DUPLICATE KEY UPDATE id = id
                """,
                postId,
                memberId,
            )
        }

        fun seedComment(
            commentId: Long,
            postId: Long,
            memberId: Long,
            content: String = "댓글 $commentId",
            parentId: Long? = null,
            status: String = "ACTIVE",
        ): Unit =
            execute(
                """
                INSERT INTO community_comment (id, post_id, member_id, parent_id, content, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, NOW(6), NOW(6))
                ON DUPLICATE KEY UPDATE id = id
                """,
                commentId,
                postId,
                memberId,
                parentId,
                content,
                status,
            )

        fun list(token: String?, postId: Long, cursor: String? = null): ResultActionsDsl =
            mockMvc.get("/api/v1/community/posts/$postId/comments") {
                cursor?.let { param("cursor", it) }
                token?.let { header("Authorization", "Bearer $it") }
            }

        fun payloadOf(result: ResultActionsDsl): JsonNode =
            mapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8)).path("payload")

        given("댓글 목록 API — GET /api/v1/community/posts/{postId}/comments") {
            `when`("댓글과 대댓글이 섞여 있으면") {
                then("최상위는 등록순으로, 대댓글은 각 댓글 아래 등록순으로 중첩된다") {
                    seedPost(8700L, 8600L)
                    seedMember(8601L)
                    seedComment(87001L, 8700L, 8601L, content = "첫 댓글")
                    seedComment(87002L, 8700L, 8600L, content = "둘째 댓글")
                    seedComment(87003L, 8700L, 8600L, content = "첫 댓글의 답1", parentId = 87001L)
                    seedComment(87004L, 8700L, 8601L, content = "첫 댓글의 답2", parentId = 87001L)
                    val token = accessToken(8602L)

                    val payload = payloadOf(
                        list(token, 8700L).andExpect {
                            status { isOk() }
                            jsonPath("$.success") { value(true) }
                        },
                    )

                    payload.path("items").size() shouldBe 2
                    payload.path("items")[0].path("commentId").asLong() shouldBe 87001L
                    payload.path("items")[0].path("author").path("nickname").asText() shouldBe "댓글독자8601"
                    payload.path("items")[0].path("replies").size() shouldBe 2
                    payload.path("items")[0].path("replies")[0].path("commentId").asLong() shouldBe 87003L
                    payload.path("items")[0].path("replies")[1].path("commentId").asLong() shouldBe 87004L
                    payload.path("items")[1].path("commentId").asLong() shouldBe 87002L
                    payload.path("items")[1].path("replies").size() shouldBe 0
                    payload.path("hasNext").asBoolean() shouldBe false
                }
            }

            `when`("최상위 댓글이 페이지 크기를 넘으면") {
                then("커서로 이어 조회하며 중복·누락이 없다") {
                    seedPost(8701L, 8600L)
                    val topIds = (1..21).map { 87100L + it }
                    topIds.forEach { seedComment(it, 8701L, 8600L) }
                    val token = accessToken(8603L)

                    val page1 = payloadOf(list(token, 8701L).andExpect { status { isOk() } })
                    page1.path("items").size() shouldBe 20
                    page1.path("hasNext").asBoolean() shouldBe true
                    val nextCursor = page1.path("nextCursor").asLong()
                    nextCursor shouldBe 87120L

                    val page2 = payloadOf(list(token, 8701L, cursor = "$nextCursor").andExpect { status { isOk() } })
                    page2.path("items").size() shouldBe 1
                    page2.path("hasNext").asBoolean() shouldBe false

                    val collected = (page1.path("items") + page2.path("items")).map { it.path("commentId").asLong() }
                    collected shouldBe topIds
                    collected.shouldBeSorted()
                }
            }

            `when`("삭제된 댓글·대댓글이 섞여 있으면") {
                then("삭제분(통삭제된 대댓글 포함)은 응답에서 제외된다") {
                    seedPost(8702L, 8600L)
                    seedComment(87201L, 8702L, 8600L, content = "살아있는 댓글")
                    seedComment(87202L, 8702L, 8600L, content = "삭제된 댓글", status = "DELETED")
                    seedComment(87203L, 8702L, 8600L, content = "통삭제된 대댓글", parentId = 87202L, status = "DELETED")
                    seedComment(87204L, 8702L, 8600L, content = "단독 삭제된 대댓글", parentId = 87201L, status = "DELETED")
                    seedComment(87205L, 8702L, 8600L, content = "살아있는 대댓글", parentId = 87201L)
                    val token = accessToken(8604L)

                    val payload = payloadOf(list(token, 8702L).andExpect { status { isOk() } })

                    payload.path("items").size() shouldBe 1
                    payload.path("items")[0].path("commentId").asLong() shouldBe 87201L
                    payload.path("items")[0].path("replies").size() shouldBe 1
                    payload.path("items")[0].path("replies")[0].path("commentId").asLong() shouldBe 87205L
                }
            }

            `when`("탈퇴한 사용자의 댓글이 있으면") {
                then("내용은 유지되고 작성자는 익명화된다") {
                    seedPost(8703L, 8600L)
                    seedMember(8605L)
                    seedComment(87301L, 8703L, 8605L, content = "탈퇴 전에 남긴 댓글")
                    withdrawMember(8605L)
                    val token = accessToken(8606L)

                    val payload = payloadOf(list(token, 8703L).andExpect { status { isOk() } })

                    val author = payload.path("items")[0].path("author")
                    author.path("memberId").isNull shouldBe true
                    author.path("nickname").asText() shouldBe "탈퇴한 사용자"
                    author.path("profileImageUrl").isNull shouldBe true
                    payload.path("items")[0].path("content").asText() shouldBe "탈퇴 전에 남긴 댓글"
                }
            }

            `when`("게스트가 목록을 조회하면") {
                then("401 로 거절한다") {
                    seedPost(8704L, 8600L)

                    list(null, 8704L).andExpect { status { isUnauthorized() } }
                }
            }

            `when`("없는 글의 목록을 조회하면") {
                then("COMMUNITY-001 로 거절한다") {
                    val token = accessToken(8607L)

                    list(token, 99999999L).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-001") }
                    }
                }
            }

            `when`("삭제된 글의 목록을 조회하면") {
                then("COMMUNITY-001 로 거절한다") {
                    seedPost(8705L, 8600L)
                    execute("UPDATE community_post SET status = 'DELETED' WHERE id = ?", 8705L)
                    val token = accessToken(8608L)

                    list(token, 8705L).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-001") }
                    }
                }
            }

            `when`("커서 형식이 잘못되면") {
                then("400 으로 거절한다") {
                    seedPost(8706L, 8600L)
                    val token = accessToken(8609L)

                    list(token, 8706L, cursor = "abc").andExpect { status { isBadRequest() } }
                }
            }
        }
    }
}
