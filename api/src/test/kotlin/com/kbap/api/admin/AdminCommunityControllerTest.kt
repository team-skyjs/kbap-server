package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.admin.AdminTestTokens.adminHeaders
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.admin.AdminAuditLogJpaRepository
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.community.CommentJpaRepository
import com.kbap.common.domain.community.PostingJpaRepository
import com.kbap.common.domain.community.model.Comment
import com.kbap.common.domain.community.model.Posting
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminCommunityControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var memberRepository: MemberJpaRepository

    @Autowired
    private lateinit var postingRepository: PostingJpaRepository

    @Autowired
    private lateinit var commentRepository: CommentJpaRepository

    @Autowired
    private lateinit var auditLogRepository: AdminAuditLogJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    private val objectMapper = jacksonObjectMapper()

    init {
        fun member(uid: String): Member = memberRepository.save(Member(providerUid = uid, email = "$uid@test.com", nickname = uid, onboardingCompleted = true))
        fun post(author: Member, content: String): Posting = postingRepository.save(Posting(memberId = author.id, content = content))
        fun comment(post: Posting, author: Member, content: String, parentId: Long? = null): Comment =
            commentRepository.save(Comment(postId = post.id, memberId = author.id, content = content, parentId = parentId))

        fun token() = AdminTestTokens.adminAccessToken(tokenIssuer)
        fun get(path: String): MvcResult = mockMvc.get("/api/admin/community$path") { adminHeaders(token()) }.andReturn()
        fun delete(path: String): MvcResult = mockMvc.delete("/api/admin/community$path") { adminHeaders(token()) }.andReturn()
        fun json(r: MvcResult): Map<String, Any?> = objectMapper.readValue(r.response.contentAsString)

        @Suppress("UNCHECKED_CAST")
        fun payload(r: MvcResult) = json(r)["payload"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        fun items(r: MvcResult) = payload(r)["items"] as List<Map<String, Any?>>

        beforeContainer {
            AdminTestTables.clear(dataSource, "report", "community_comment", "community_post", "admin_audit_log", "member")
        }

        afterSpec {
            AdminTestTables.clear(dataSource, "report", "community_comment", "community_post", "admin_audit_log", "member")
        }

        given("GET /api/admin/community/posts") {
            `when`("검색어·작성자로 조회하면") {
                then("최신순으로 오고 댓글 수가 붙는다") {
                    val a = member("alice")
                    val b = member("bob")
                    val first = post(a, "첫 글")
                    val second = post(b, "두번째 글")
                    comment(second, a, "댓글")

                    items(get("/posts")).map { it["id"] } shouldContainExactly listOf(second.id.toInt(), first.id.toInt())
                    items(get("/posts?q=두번째")).map { it["id"] } shouldContainExactly listOf(second.id.toInt())
                    items(get("/posts?memberId=${a.id}")).map { it["id"] } shouldContainExactly listOf(first.id.toInt())
                    val top = items(get("/posts")).first()
                    top["memberNickname"] shouldBe "bob"
                    top["commentCount"] shouldBe 1
                    top["reportCount"] shouldBe 0
                }
            }
        }

        given("GET /api/admin/community/posts/{id}/comments") {
            `when`("삭제된 대댓글이 섞여 있으면") {
                then("삭제 포함 1depth 트리로 온다") {
                    val a = member("alice")
                    val post = post(a, "글")
                    val top = comment(post, a, "최상위")
                    val reply = comment(post, a, "대댓글", parentId = top.id)
                    val deletedReply = comment(post, a, "삭제된 대댓글", parentId = top.id)
                    AdminTestTables.execute(dataSource, "UPDATE community_comment SET status = 'DELETED' WHERE id = ${deletedReply.id}")

                    val result = get("/posts/${post.id}/comments")
                    result.response.status shouldBe 200
                    val p = payload(result)
                    p["totalCount"] shouldBe 3
                    @Suppress("UNCHECKED_CAST")
                    val comments = p["comments"] as List<Map<String, Any?>>
                    comments.size shouldBe 1
                    @Suppress("UNCHECKED_CAST")
                    val replies = comments.single()["replies"] as List<Map<String, Any?>>
                    replies.map { it["id"] } shouldContainExactly listOf(reply.id.toInt(), deletedReply.id.toInt())
                    replies.map { it["deleted"] } shouldContainExactly listOf(false, true)
                }
            }

            `when`("없는 게시글이면") {
                then("400 COMMUNITY-001") {
                    val result = get("/posts/999999/comments")
                    result.response.status shouldBe 400
                    json(result)["code"] shouldBe "COMMUNITY-001"
                }
            }
        }

        given("DELETE /api/admin/community/posts/{id} · /comments/{id}") {
            `when`("게시글과 최상위 댓글을 블라인드하면") {
                then("소프트 삭제되고 대댓글도 함께 숨겨지며 감사 로그가 남는다") {
                    val a = member("alice")
                    val post = post(a, "글")
                    val top = comment(post, a, "최상위")
                    val reply = comment(post, a, "대댓글", parentId = top.id)

                    delete("/comments/${top.id}").response.status shouldBe 200
                    commentRepository.findById(top.id).isPresent shouldBe false
                    commentRepository.findById(reply.id).isPresent shouldBe false

                    delete("/posts/${post.id}").response.status shouldBe 200
                    postingRepository.findById(post.id).isPresent shouldBe false
                    auditLogRepository.findAll().map { it.action } shouldContainExactly listOf(AdminAuditAction.COMMENT_DELETE, AdminAuditAction.POST_DELETE)

                    delete("/posts/${post.id}").response.status shouldBe 400
                }
            }
        }
    }
}
