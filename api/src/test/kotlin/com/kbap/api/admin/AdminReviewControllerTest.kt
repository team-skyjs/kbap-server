package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.admin.AdminTestTokens.adminHeaders
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.admin.AdminAuditLogJpaRepository
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.MemberRankingEventJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.RankingEventType
import com.kbap.common.domain.report.ReportJpaRepository
import com.kbap.common.domain.report.model.Report
import com.kbap.common.domain.report.model.ReportReason
import com.kbap.common.domain.report.model.ReportTargetType
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.domain.review.model.Review
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminReviewControllerTest : BehaviorSpec() {
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
    private lateinit var reportRepository: ReportJpaRepository

    @Autowired
    private lateinit var rankingEventRepository: MemberRankingEventJpaRepository

    @Autowired
    private lateinit var auditLogRepository: AdminAuditLogJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    private val objectMapper = jacksonObjectMapper()

    init {
        fun member(uid: String): Member = memberRepository.save(Member(providerUid = uid, email = "$uid@test.com", nickname = uid, onboardingCompleted = true))
        fun food(name: String): Food = foodRepository.save(Food(koreanName = name, description = "설명", contentStatus = FoodContentStatus.READY))
        fun review(author: Member, food: Food, content: String? = null, images: List<String>? = null): Review =
            reviewRepository.save(Review(memberId = author.id, foodId = food.id, rating = 4, content = content, imageRefs = images))

        fun token() = AdminTestTokens.adminAccessToken(tokenIssuer)
        fun get(query: String = ""): MvcResult = mockMvc.get("/api/admin/reviews$query") { adminHeaders(token()) }.andReturn()
        fun json(r: MvcResult): Map<String, Any?> = objectMapper.readValue(r.response.contentAsString)

        @Suppress("UNCHECKED_CAST")
        fun payload(r: MvcResult) = json(r)["payload"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        fun ids(r: MvcResult) = (payload(r)["items"] as List<Map<String, Any?>>).map { (it["id"] as Number).toLong() }

        beforeContainer {
            AdminTestTables.clear(dataSource, "report", "member_ranking_event", "review_like", "food_review", "admin_audit_log", "food", "member")
        }

        afterSpec {
            AdminTestTables.clear(dataSource, "report", "member_ranking_event", "review_like", "food_review", "admin_audit_log", "food", "member")
        }

        given("GET /api/admin/reviews") {
            `when`("검색·신고·사진 필터를 조합하면") {
                then("조건에 맞는 리뷰만 최신순으로 오고 닉네임·음식명·신고 수가 붙는다") {
                    val a = member("alice")
                    val b = member("bob")
                    val kimchi = food("김치찌개")
                    val plain = review(a, kimchi, content = "맛있어요")
                    val withImage = review(b, kimchi, content = "사진 리뷰", images = listOf("images/review/1.webp"))
                    val reported = review(a, food("된장찌개"), content = "광고 링크")
                    reportRepository.save(Report(reporterMemberId = b.id, targetType = ReportTargetType.REVIEW, targetId = reported.id, reason = ReportReason.SPAM))

                    ids(get()) shouldContainExactly listOf(reported.id, withImage.id, plain.id)
                    ids(get("?hasImage=true")) shouldContainExactly listOf(withImage.id)
                    ids(get("?hasImage=false")) shouldContainExactly listOf(reported.id, plain.id)
                    ids(get("?reported=true")) shouldContainExactly listOf(reported.id)
                    ids(get("?q=광고")) shouldContainExactly listOf(reported.id)
                    ids(get("?q=${plain.id}")) shouldContainExactly listOf(plain.id)
                    ids(get("?memberId=${b.id}")) shouldContainExactly listOf(withImage.id)
                    ids(get("?foodId=${kimchi.id}")) shouldContainExactly listOf(withImage.id, plain.id)

                    @Suppress("UNCHECKED_CAST")
                    val top = (payload(get())["items"] as List<Map<String, Any?>>).first()
                    top["memberNickname"] shouldBe "alice"
                    top["foodDisplayName"] shouldBe "된장찌개"
                    top["reportCount"] shouldBe 1
                    top["likeCount"] shouldBe 0
                }
            }
        }

        given("DELETE /api/admin/reviews/{id}") {
            `when`("활성 작성자의 리뷰를 삭제하면") {
                then("소프트 삭제 + 리뷰 수 차감 + 랭킹 원장 REVIEW_DELETED + 감사 로그") {
                    val a = member("alice")
                    val review = review(a, food("김치찌개"))
                    AdminTestTables.execute(dataSource, "UPDATE member SET review_count = 1, unique_reviewed_food_count = 1 WHERE id = ${a.id}")

                    val result = mockMvc.delete("/api/admin/reviews/${review.id}") { adminHeaders(token()) }.andReturn()
                    result.response.status shouldBe 200
                    payload(result)["rankingAdjusted"] shouldBe true

                    reviewRepository.findById(review.id).isPresent shouldBe false
                    val member = memberRepository.findById(a.id).get()
                    member.reviewCount shouldBe 0
                    member.uniqueReviewedFoodCount shouldBe 0
                    rankingEventRepository.existsByReviewIdAndEvent(review.id, RankingEventType.REVIEW_DELETED) shouldBe true
                    auditLogRepository.findAll().single().action shouldBe AdminAuditAction.REVIEW_DELETE
                }
            }

            `when`("없는 리뷰면") {
                then("400 REVIEW-001") {
                    val result = mockMvc.delete("/api/admin/reviews/999999") { adminHeaders(token()) }.andReturn()
                    result.response.status shouldBe 400
                    json(result)["code"] shouldBe "REVIEW-001"
                }
            }
        }

        given("PATCH /api/admin/reviews/{id}/images") {
            `when`("사진이 있는 리뷰에 호출하면") {
                then("사진만 비워지고 본문은 남는다") {
                    val review = review(member("alice"), food("김치찌개"), content = "본문", images = listOf("images/review/1.webp"))
                    val result = mockMvc.patch("/api/admin/reviews/${review.id}/images") { adminHeaders(token()) }.andReturn()
                    result.response.status shouldBe 200
                    val p = payload(result)
                    (p["imageUrls"] as List<*>).size shouldBe 0
                    p["content"] shouldBe "본문"
                    reviewRepository.findById(review.id).get().imageRefs.shouldBeNull()
                    auditLogRepository.findAll().single().action shouldBe AdminAuditAction.REVIEW_IMAGES_REMOVE
                }
            }
        }
    }
}
