package com.kbap.api.review

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.admin.AdminMemberQueryService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.SocialIdentity
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.domain.review.ReviewSort
import com.kbap.common.domain.review.model.Review
import com.kbap.common.domain.review.model.ReviewTargetType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

@IntegrationTest
class ReviewTargetReadGuardTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var reviewService: ReviewService
    @Autowired private lateinit var adminMembers: AdminMemberQueryService
    @Autowired private lateinit var reviewRepository: ReviewJpaRepository
    @Autowired private lateinit var memberRepository: MemberJpaRepository
    @Autowired private lateinit var foodRepository: FoodJpaRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var dataSource: DataSource

    init {
        fun sql(statement: String): Long = dataSource.connection.use { c ->
            c.createStatement().use { st ->
                if (statement.trimStart().startsWith("SELECT")) st.executeQuery(statement).use { rs -> rs.next(); rs.getLong(1) }
                else { st.executeUpdate(statement, java.sql.Statement.RETURN_GENERATED_KEYS); st.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else 0L } }
            }
        }
        fun member(tag: String) = memberRepository.save(Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "$tag-${System.nanoTime()}", null)))

        data class Seed(val author: Member, val food: Food, val foodReviewId: Long, val productReviewId: Long)
        fun seed(): Seed {
            val author = member("target-author")
            val food = foodRepository.save(Food(koreanName = "대상가드음식", description = "설명", contentStatus = FoodContentStatus.READY))
            val foodReview = reviewService.createReview(author.id, food.id, 5, null, null, "음식에 남긴 일반 리뷰입니다", null, null)
            val productId = sql(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES ('G${System.nanoTime() % 10_000_000_000}', '대상가드과자', '대상가드과자', '과자', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
            )
            val productReviewId = sql(
                "INSERT INTO food_review (member_id, target_type, food_id, processed_food_id, rating, serving_speed_rating, staff_kindness_rating, content, status, created_at, updated_at, version) " +
                    "VALUES (${author.id}, 'PROCESSED_FOOD', NULL, $productId, 4, 0, 0, '가공식품에 남긴 리뷰입니다', 'ACTIVE', NOW(6), NOW(6), 0)",
            )
            sql("UPDATE member SET review_count = review_count + 1 WHERE id = ${author.id}")
            return Seed(author, food, foodReview.reviewId, productReviewId)
        }

        beforeEach { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("가공식품 리뷰 행이 이미 있는 DB(①-b 뒤 ①-a 로 롤백한 상태)") {
            `when`("공개 피드·내 리뷰·리뷰 단건을 읽으면") {
                then("음식 리뷰만 보이고 가공식품 리뷰는 404 로 숨는다") {
                    val s = seed()
                    val viewer = member("target-viewer")
                    reviewService.getReviewPage(viewer.id, null, null, LanguageCode.EN, ReviewSort.LATEST, true, null, null, null).items.map { it.reviewId } shouldBe listOf(s.foodReviewId)
                    reviewService.getMyReviewPage(s.author.id, LanguageCode.EN, null).items.map { it.reviewId } shouldBe listOf(s.foodReviewId)
                    shouldThrow<BusinessException> { reviewService.getVisibleReview(viewer.id, s.productReviewId) }.errorCode shouldBe ErrorCode.REVIEW_NOT_FOUND
                }
            }
            `when`("어드민이 그 회원의 리뷰 목록을 보면") {
                then("두 리뷰가 다 보이고 가공식품 리뷰의 음식 칸은 비어 있다") {
                    val s = seed()
                    val items = adminMembers.getMemberReviewPage(s.author.id, 1).items.associateBy { it.id }
                    items.keys shouldBe setOf(s.foodReviewId, s.productReviewId)
                    items.getValue(s.productReviewId).foodId shouldBe null
                    items.getValue(s.productReviewId).foodName shouldBe null
                }
            }
            `when`("작성자가 가공식품 리뷰를 지우면") {
                then("500 없이 지워지고 음식 리뷰 수·고유 음식 수는 그대로다") {
                    val s = seed()
                    val uniqueBefore = sql("SELECT unique_reviewed_food_count FROM member WHERE id = ${s.author.id}")
                    reviewService.deleteReview(s.author.id, s.productReviewId)
                    sql("SELECT COUNT(*) FROM food_review WHERE id = ${s.productReviewId} AND status = 'DELETED'") shouldBe 1
                    sql("SELECT review_count FROM food WHERE id = ${s.food.id}") shouldBe 1
                    sql("SELECT unique_reviewed_food_count FROM member WHERE id = ${s.author.id}") shouldBe uniqueBefore
                    sql("SELECT review_count FROM member WHERE id = ${s.author.id}") shouldBe 1
                }
            }
            `when`("신고 처리로 지우거나 다른 회원이 좋아요를 누르면") {
                then("500 없이 처리된다") {
                    val s = seed()
                    reviewService.likeReview(member("target-liker").id, s.productReviewId)
                    sql("SELECT COUNT(*) FROM review_like WHERE review_id = ${s.productReviewId}") shouldBe 1
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        reviewService.deleteForModeration(reviewRepository.findAnyByIdForUpdate(s.productReviewId)!!)
                    }
                    sql("SELECT COUNT(*) FROM food_review WHERE id = ${s.productReviewId} AND status = 'DELETED'") shouldBe 1
                    sql("SELECT review_count FROM food WHERE id = ${s.food.id}") shouldBe 1
                }
            }
        }

        given("리뷰 대상 불변식") {
            `when`("타입과 대상 id 가 어긋나면") {
                then("엔티티 생성이 거절된다(DB CHECK 와 같은 규칙)") {
                    shouldThrow<IllegalArgumentException> { Review(memberId = 1, foodId = null, rating = 5) }
                    shouldThrow<IllegalArgumentException> { Review(memberId = 1, foodId = 1, rating = 5, targetType = ReviewTargetType.PROCESSED_FOOD, processedFoodId = 2) }
                    shouldThrow<IllegalArgumentException> { Review(memberId = 1, foodId = null, rating = 5, targetType = ReviewTargetType.PROCESSED_FOOD) }
                    Review(memberId = 1, foodId = null, rating = 5, targetType = ReviewTargetType.PROCESSED_FOOD, processedFoodId = 2).foodId shouldBe null
                }
            }
        }
    }
}
