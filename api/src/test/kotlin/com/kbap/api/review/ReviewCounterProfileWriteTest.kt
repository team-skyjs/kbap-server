package com.kbap.api.review

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.SocialIdentity
import com.kbap.common.domain.member.model.SocialProvider
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

@IntegrationTest
class ReviewCounterProfileWriteTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var reviewService: ReviewService
    @Autowired private lateinit var memberRepository: MemberJpaRepository
    @Autowired private lateinit var foodRepository: FoodJpaRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var dataSource: DataSource

    init {
        fun counters(memberId: Long): Pair<Int, Int> = dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT review_count, unique_reviewed_food_count FROM member WHERE id = $memberId").use { rs -> rs.next(); rs.getInt(1) to rs.getInt(2) }
            }
        }

        beforeEach { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("회원 행을 읽어 둔 프로필 저장과 리뷰 삭제가 겹칠 때") {
            `when`("삭제로 리뷰 카운터가 줄어든 뒤 먼저 읽힌 회원 엔티티가 프로필 변경으로 저장되면") {
                then("리뷰 카운터는 저장 시점의 옛 값으로 덮이지 않는다") {
                    val first = foodRepository.save(Food(koreanName = "카운터음식일", description = "설명", contentStatus = FoodContentStatus.READY))
                    val second = foodRepository.save(Food(koreanName = "카운터음식이", description = "설명", contentStatus = FoodContentStatus.READY))
                    val member = memberRepository.save(Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "counter-${System.nanoTime()}", null)))
                    reviewService.createReview(member.id, first.id, 5, null, null, "첫 번째 음식 리뷰입니다", null, null)
                    val removed = reviewService.createReview(member.id, second.id, 4, null, null, "두 번째 음식 리뷰입니다", null, null)
                    counters(member.id) shouldBe (2 to 2)

                    val independent = TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        val loaded = memberRepository.findById(member.id).orElseThrow()
                        independent.executeWithoutResult { reviewService.deleteReview(member.id, removed.reviewId) }
                        loaded.updateProfile(nickname = "카운터닉네임")
                    }

                    counters(member.id) shouldBe (1 to 1)
                    memberRepository.findById(member.id).orElseThrow().nickname shouldBe "카운터닉네임"
                }
            }
        }
    }
}
