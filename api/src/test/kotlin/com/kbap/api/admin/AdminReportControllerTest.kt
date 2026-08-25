package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.admin.AdminTestTokens.adminHeaders
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.admin.AdminAuditLogJpaRepository
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.community.PostingJpaRepository
import com.kbap.common.domain.community.model.Posting
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.report.ReportJpaRepository
import com.kbap.common.domain.report.model.Report
import com.kbap.common.domain.report.model.ReportHandleStatus
import com.kbap.common.domain.report.model.ReportReason
import com.kbap.common.domain.report.model.ReportTargetType
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.domain.review.model.Review
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminReportControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var memberRepository: MemberJpaRepository

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var reviewRepository: ReviewJpaRepository

    @Autowired
    private lateinit var postingRepository: PostingJpaRepository

    @Autowired
    private lateinit var reportRepository: ReportJpaRepository

    @Autowired
    private lateinit var auditLogRepository: AdminAuditLogJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    private val objectMapper = jacksonObjectMapper()

    init {
        fun member(uid: String): Member = memberRepository.save(Member(providerUid = uid, email = "$uid@test.com", nickname = uid, onboardingCompleted = true))
        fun food(): Food = foodRepository.save(Food(koreanName = "신고음식", description = "설명", contentStatus = FoodContentStatus.READY))
        fun review(author: Member, food: Food, content: String = "리뷰 본문"): Review = reviewRepository.save(Review(memberId = author.id, foodId = food.id, rating = 3, content = content))
        fun report(reporter: Member, type: ReportTargetType, targetId: Long, reason: ReportReason = ReportReason.SPAM): Report =
            reportRepository.save(Report(reporterMemberId = reporter.id, targetType = type, targetId = targetId, reason = reason))

        fun token() = AdminTestTokens.adminAccessToken(tokenIssuer)
        fun get(query: String = ""): MvcResult = mockMvc.get("/api/admin/reports$query") { adminHeaders(token()) }.andReturn()
        fun handle(id: Long, body: String): MvcResult =
            mockMvc.patch("/api/admin/reports/$id") {
                adminHeaders(token())
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()

        fun json(r: MvcResult): Map<String, Any?> = objectMapper.readValue(r.response.contentAsString)

        @Suppress("UNCHECKED_CAST")
        fun payload(r: MvcResult) = json(r)["payload"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        fun items(r: MvcResult) = payload(r)["items"] as List<Map<String, Any?>>

        beforeContainer {
            AdminTestTables.clear(dataSource, "report", "member_ranking_event", "food_review", "community_comment", "community_post", "admin_audit_log", "food", "member")
        }

        afterSpec {
            AdminTestTables.clear(dataSource, "report", "member_ranking_event", "food_review", "community_comment", "community_post", "admin_audit_log", "food", "member")
        }

        given("GET /api/admin/reports") {
            `when`("같은 리뷰에 신고가 2건, 게시글에 1건 있으면") {
                then("최신순으로 오고 대상별 누적 건수·작성자·본문 미리보기가 붙는다") {
                    val author = member("author")
                    val r1 = member("r1")
                    val r2 = member("r2")
                    val review = review(author, food(), content = "x".repeat(120))
                    val post = postingRepository.save(Posting(memberId = author.id, content = "게시글"))
                    report(r1, ReportTargetType.REVIEW, review.id)
                    report(r2, ReportTargetType.REVIEW, review.id, ReportReason.ABUSE)
                    report(r1, ReportTargetType.POST, post.id)

                    val all = items(get())
                    all.size shouldBe 3
                    @Suppress("UNCHECKED_CAST")
                    val reviewTarget = all.last()["target"] as Map<String, Any?>
                    reviewTarget["type"] shouldBe "REVIEW"
                    reviewTarget["reportCount"] shouldBe 2
                    reviewTarget["authorMemberId"] shouldBe author.id.toInt()
                    (reviewTarget["contentPreview"] as String).length shouldBe 80
                    reviewTarget["exists"] shouldBe true
                    all.last()["reporterNickname"] shouldBe "r1"

                    items(get("?targetType=POST")).size shouldBe 1
                    items(get("?reason=ABUSE")).size shouldBe 1
                    items(get("?status=HANDLED")).size shouldBe 0
                }
            }
        }

        given("PATCH /api/admin/reports/{id}") {
            `when`("CONTENT_DELETED 로 처리하면") {
                then("리뷰가 소프트 삭제되고 같은 대상의 다른 신고도 함께 처리된다") {
                    val author = member("author")
                    val review = review(author, food())
                    val first = report(member("r1"), ReportTargetType.REVIEW, review.id)
                    val second = report(member("r2"), ReportTargetType.REVIEW, review.id)

                    val result = handle(first.id, """{"result":"CONTENT_DELETED","note":"광고"}""")
                    result.response.status shouldBe 200
                    val p = payload(result)
                    (p["handledReportIds"] as List<*>).map { (it as Number).toLong() } shouldContainExactlyInAnyOrder listOf(first.id, second.id)
                    @Suppress("UNCHECKED_CAST")
                    val report = p["report"] as Map<String, Any?>
                    report["handleStatus"] shouldBe "HANDLED"
                    report["handleResult"] shouldBe "CONTENT_DELETED"
                    report["handledBy"] shouldBe 1

                    reviewRepository.findById(review.id).isPresent shouldBe false
                    reportRepository.findById(second.id).get().handleStatus shouldBe ReportHandleStatus.HANDLED
                    auditLogRepository.findAll().map { it.action } shouldContainExactlyInAnyOrder listOf(AdminAuditAction.REVIEW_DELETE, AdminAuditAction.REPORT_HANDLE)
                    items(get("?status=PENDING")).size shouldBe 0
                }
            }

            `when`("MEMBER_SUSPENDED 로 처리하면") {
                then("note 없이는 400, note 가 있으면 작성자가 정지된다") {
                    val author = member("author")
                    val review = review(author, food())
                    val report = report(member("r1"), ReportTargetType.REVIEW, review.id)

                    handle(report.id, """{"result":"MEMBER_SUSPENDED"}""").response.status shouldBe 400

                    handle(report.id, """{"result":"MEMBER_SUSPENDED","note":"반복 도배"}""").response.status shouldBe 200
                    val suspended = memberRepository.findById(author.id).get()
                    suspended.memberStatus shouldBe MemberStatus.SUSPENDED
                    suspended.suspendReason shouldBe "반복 도배"
                    reviewRepository.findById(review.id).isPresent shouldBe true
                }
            }

            `when`("이미 처리된 신고를 다시 처리하면") {
                then("409 REPORT-004") {
                    val review = review(member("author"), food())
                    val report = report(member("r1"), ReportTargetType.REVIEW, review.id)
                    handle(report.id, """{"result":"DISMISSED"}""").response.status shouldBe 200
                    val again = handle(report.id, """{"result":"DISMISSED"}""")
                    again.response.status shouldBe 409
                    json(again)["code"] shouldBe "REPORT-004"
                }
            }

            `when`("대상이 이미 삭제된 신고를 CONTENT_DELETED 로 처리하면") {
                then("건너뛰고 처리 완료로 표시된다") {
                    val review = review(member("author"), food())
                    val report = report(member("r1"), ReportTargetType.REVIEW, review.id)
                    AdminTestTables.execute(dataSource, "UPDATE food_review SET status = 'DELETED' WHERE id = ${review.id}")

                    val result = handle(report.id, """{"result":"CONTENT_DELETED"}""")
                    result.response.status shouldBe 200
                    @Suppress("UNCHECKED_CAST")
                    val target = (payload(result)["report"] as Map<String, Any?>)["target"] as Map<String, Any?>
                    target["exists"] shouldBe false
                    target["authorMemberId"].shouldBeNull()
                }
            }

            `when`("없는 신고면") {
                then("404 REPORT-005") {
                    val result = handle(999_999, """{"result":"DISMISSED"}""")
                    result.response.status shouldBe 404
                    json(result)["code"] shouldBe "REPORT-005"
                }
            }
        }
    }
}
