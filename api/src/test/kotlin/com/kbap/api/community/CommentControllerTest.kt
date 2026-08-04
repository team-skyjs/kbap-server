package com.kbap.api.community

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
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class CommentControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        fun seedMember(memberId: Long): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO member (id, provider, provider_uid, nickname, country_code, member_status,
                                        onboarding_completed, status, created_at, updated_at)
                    VALUES (?, 'GOOGLE', ?, ?, 'KR', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE id = id
                    """,
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.setString(2, "comment-test-$memberId")
                    ps.setString(3, "댓글러$memberId")
                    ps.executeUpdate()
                }
            }

        fun accessToken(memberId: Long): String {
            seedMember(memberId)
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun seedPost(postId: Long, memberId: Long): Unit =
            dataSource.connection.use { c ->
                seedMember(memberId)
                c.prepareStatement(
                    """
                    INSERT INTO community_post (id, member_id, content, status, created_at, updated_at)
                    VALUES (?, ?, '댓글 테스트 글', 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE id = id
                    """,
                ).use { ps ->
                    ps.setLong(1, postId)
                    ps.setLong(2, memberId)
                    ps.executeUpdate()
                }
            }

        fun softDeletePost(postId: Long): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement("UPDATE community_post SET status = 'DELETED' WHERE id = ?").use { ps ->
                    ps.setLong(1, postId)
                    ps.executeUpdate()
                }
            }

        fun commentBody(content: String? = "정말 맛있죠", parentCommentId: Long? = null): String =
            mapper.writeValueAsString(
                buildMap {
                    content?.let { put("content", it) }
                    parentCommentId?.let { put("parentCommentId", it) }
                },
            )

        fun create(token: String?, postId: Long, body: String): ResultActionsDsl =
            mockMvc.post("/api/v1/community/posts/$postId/comments") {
                token?.let { header("Authorization", "Bearer $it") }
                contentType = MediaType.APPLICATION_JSON
                content = body
            }

        fun update(token: String, commentId: Long, body: String): ResultActionsDsl =
            mockMvc.put("/api/v1/community/comments/$commentId") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = body
            }

        fun remove(token: String, commentId: Long): ResultActionsDsl =
            mockMvc.delete("/api/v1/community/comments/$commentId") {
                header("Authorization", "Bearer $token")
            }

        fun commentIdOf(result: ResultActionsDsl): Long =
            mapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8))
                .path("payload").path("commentId").asLong()

        fun statusOf(commentId: Long): String =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT status FROM community_comment WHERE id = ?").use { ps ->
                    ps.setLong(1, commentId)
                    ps.executeQuery().use { rs ->
                        rs.next().shouldBeTrue()
                        rs.getString(1)
                    }
                }
            }

        given("댓글 작성 API — POST /api/v1/community/posts/{postId}/comments") {
            seedPost(8100L, 8000L)

            `when`("최상위 댓글을 작성하면") {
                then("200 과 댓글을 반환한다") {
                    val token = accessToken(8001L)

                    create(token, 8100L, commentBody(content = "첫 댓글")).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.postId") { value(8100) }
                        jsonPath("$.payload.content") { value("첫 댓글") }
                        jsonPath("$.payload.parentCommentId") { value(null) }
                        jsonPath("$.payload.editedAt") { value(null) }
                    }
                }
            }

            `when`("댓글에 답글을 작성하면") {
                then("해당 댓글의 대댓글로 저장된다") {
                    val token = accessToken(8002L)
                    val parentId = commentIdOf(
                        create(token, 8100L, commentBody(content = "부모 댓글")).andExpect { status { isOk() } },
                    )

                    create(token, 8100L, commentBody(content = "답글", parentCommentId = parentId)).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.parentCommentId") { value(parentId) }
                    }
                }
            }

            `when`("대댓글에 답글을 작성하면") {
                then("최상위 댓글의 대댓글로 정규화되어 저장된다") {
                    val token = accessToken(8003L)
                    val topId = commentIdOf(
                        create(token, 8100L, commentBody(content = "루트")).andExpect { status { isOk() } },
                    )
                    val replyId = commentIdOf(
                        create(token, 8100L, commentBody(content = "대댓글", parentCommentId = topId))
                            .andExpect { status { isOk() } },
                    )

                    create(token, 8100L, commentBody(content = "@댓글러 답의 답", parentCommentId = replyId)).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.parentCommentId") { value(topId) }
                    }
                }
            }

            `when`("본문이 비어 있으면") {
                then("400 으로 거절한다") {
                    val token = accessToken(8004L)

                    create(token, 8100L, commentBody(content = null)).andExpect { status { isBadRequest() } }
                    create(token, 8100L, commentBody(content = "  ")).andExpect { status { isBadRequest() } }
                }
            }

            `when`("본문이 2000자를 넘으면") {
                then("400 으로 거절한다") {
                    val token = accessToken(8005L)

                    create(token, 8100L, commentBody(content = "가".repeat(2001)))
                        .andExpect { status { isBadRequest() } }
                }
            }

            `when`("게스트가 작성을 시도하면") {
                then("401 로 거절한다") {
                    create(null, 8100L, commentBody()).andExpect { status { isUnauthorized() } }
                }
            }

            `when`("없는 글에 작성하면") {
                then("COMMUNITY-001 로 거절한다") {
                    val token = accessToken(8006L)

                    create(token, 99999999L, commentBody()).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-001") }
                    }
                }
            }

            `when`("삭제된 글에 작성하면") {
                then("COMMUNITY-001 로 거절한다") {
                    val token = accessToken(8007L)
                    seedPost(8101L, 8000L)
                    softDeletePost(8101L)

                    create(token, 8101L, commentBody()).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-001") }
                    }
                }
            }

            `when`("없는 댓글을 부모로 지정하면") {
                then("COMMUNITY-006 으로 거절한다") {
                    val token = accessToken(8008L)

                    create(token, 8100L, commentBody(parentCommentId = 99999999L)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-006") }
                    }
                }
            }

            `when`("다른 글의 댓글을 부모로 지정하면") {
                then("COMMUNITY-006 으로 거절한다") {
                    val token = accessToken(8009L)
                    seedPost(8102L, 8000L)
                    val otherPostCommentId = commentIdOf(
                        create(token, 8102L, commentBody(content = "다른 글 댓글")).andExpect { status { isOk() } },
                    )

                    create(token, 8100L, commentBody(parentCommentId = otherPostCommentId)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-006") }
                    }
                }
            }

            `when`("삭제된 댓글을 부모로 지정하면") {
                then("COMMUNITY-006 으로 거절한다") {
                    val token = accessToken(8010L)
                    val deletedId = commentIdOf(
                        create(token, 8100L, commentBody(content = "곧 삭제")).andExpect { status { isOk() } },
                    )
                    dataSource.connection.use { c ->
                        c.prepareStatement("UPDATE community_comment SET status = 'DELETED' WHERE id = ?").use { ps ->
                            ps.setLong(1, deletedId)
                            ps.executeUpdate()
                        }
                    }

                    create(token, 8100L, commentBody(parentCommentId = deletedId)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-006") }
                    }
                }
            }
        }

        given("댓글 수정 API — PUT /api/v1/community/comments/{commentId}") {
            seedPost(8200L, 8000L)

            `when`("본인 댓글의 본문을 수정하면") {
                then("반영되고 editedAt 이 채워진다") {
                    val token = accessToken(8201L)
                    val commentId = commentIdOf(
                        create(token, 8200L, commentBody(content = "수정 전")).andExpect { status { isOk() } },
                    )

                    update(token, commentId, commentBody(content = "수정 후")).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.content") { value("수정 후") }
                        jsonPath("$.payload.editedAt") { exists() }
                    }
                }
            }

            `when`("타인의 댓글을 수정하려 하면") {
                then("403 COMMUNITY-007 로 거절한다") {
                    val ownerToken = accessToken(8202L)
                    val commentId = commentIdOf(
                        create(ownerToken, 8200L, commentBody(content = "내 댓글")).andExpect { status { isOk() } },
                    )
                    val otherToken = accessToken(8203L)

                    update(otherToken, commentId, commentBody(content = "남의 댓글")).andExpect {
                        status { isForbidden() }
                        jsonPath("$.code") { value("COMMUNITY-007") }
                    }
                }
            }

            `when`("없는 댓글을 수정하려 하면") {
                then("COMMUNITY-006 으로 거절한다") {
                    val token = accessToken(8204L)

                    update(token, 99999999L, commentBody(content = "없는 댓글")).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-006") }
                    }
                }
            }

            `when`("수정 내용이 본문 제약을 위반하면") {
                then("400 으로 거절한다") {
                    val token = accessToken(8205L)
                    val commentId = commentIdOf(
                        create(token, 8200L, commentBody(content = "원본")).andExpect { status { isOk() } },
                    )

                    update(token, commentId, commentBody(content = "가".repeat(2001)))
                        .andExpect { status { isBadRequest() } }
                }
            }
        }

        given("댓글 삭제 API — DELETE /api/v1/community/comments/{commentId}") {
            seedPost(8300L, 8000L)

            `when`("대댓글이 달린 최상위 댓글을 삭제하면") {
                then("대댓글까지 모두 DELETED 로 전환된다") {
                    val token = accessToken(8301L)
                    val topId = commentIdOf(
                        create(token, 8300L, commentBody(content = "통삭제 대상")).andExpect { status { isOk() } },
                    )
                    val replyIds = (1..2).map {
                        commentIdOf(
                            create(token, 8300L, commentBody(content = "대댓글$it", parentCommentId = topId))
                                .andExpect { status { isOk() } },
                        )
                    }

                    remove(token, topId).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }

                    statusOf(topId) shouldBe "DELETED"
                    replyIds.forEach { statusOf(it) shouldBe "DELETED" }
                }
            }

            `when`("대댓글만 삭제하면") {
                then("해당 대댓글만 DELETED 로 전환된다") {
                    val token = accessToken(8302L)
                    val topId = commentIdOf(
                        create(token, 8300L, commentBody(content = "부모 유지")).andExpect { status { isOk() } },
                    )
                    val reply1 = commentIdOf(
                        create(token, 8300L, commentBody(content = "삭제될 대댓글", parentCommentId = topId))
                            .andExpect { status { isOk() } },
                    )
                    val reply2 = commentIdOf(
                        create(token, 8300L, commentBody(content = "남을 대댓글", parentCommentId = topId))
                            .andExpect { status { isOk() } },
                    )

                    remove(token, reply1).andExpect { status { isOk() } }

                    statusOf(reply1) shouldBe "DELETED"
                    statusOf(topId) shouldBe "ACTIVE"
                    statusOf(reply2) shouldBe "ACTIVE"
                }
            }

            `when`("타인의 댓글을 삭제하려 하면") {
                then("403 COMMUNITY-007 로 거절한다") {
                    val ownerToken = accessToken(8303L)
                    val commentId = commentIdOf(
                        create(ownerToken, 8300L, commentBody(content = "내 댓글")).andExpect { status { isOk() } },
                    )
                    val otherToken = accessToken(8304L)

                    remove(otherToken, commentId).andExpect {
                        status { isForbidden() }
                        jsonPath("$.code") { value("COMMUNITY-007") }
                    }
                    statusOf(commentId) shouldBe "ACTIVE"
                }
            }

            `when`("삭제한 댓글을 다시 수정·삭제하려 하면") {
                then("COMMUNITY-006 으로 거절한다") {
                    val token = accessToken(8305L)
                    val commentId = commentIdOf(
                        create(token, 8300L, commentBody(content = "곧 삭제")).andExpect { status { isOk() } },
                    )
                    remove(token, commentId).andExpect { status { isOk() } }

                    update(token, commentId, commentBody(content = "부활")).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-006") }
                    }
                    remove(token, commentId).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-006") }
                    }
                }
            }
        }
    }
}
