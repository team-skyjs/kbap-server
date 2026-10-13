package com.kbap.api.review

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.SocialIdentity
import com.kbap.common.domain.member.model.SocialProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import javax.sql.DataSource

@IntegrationTest
class ReviewMemberGuardTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var reviewService: ReviewService
    @Autowired private lateinit var memberRepository: MemberJpaRepository
    @Autowired private lateinit var foodRepository: FoodJpaRepository
    @Autowired private lateinit var dataSource: DataSource

    init {
        fun count(sql: String): Long = dataSource.connection.use { c ->
            c.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }
        }

        beforeEach { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("리뷰 작성의 회원 확인") {
            `when`("없는 회원이나 정지된 회원이 쓰면") {
                then("MEMBER_NOT_FOUND 로 거절하고 리뷰·카운터를 남기지 않는다") {
                    val food = foodRepository.save(Food(koreanName = "회원가드음식", description = "설명", contentStatus = FoodContentStatus.READY))
                    val suspended = memberRepository.save(Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "guard-${System.nanoTime()}", null)))
                    dataSource.connection.use { c -> c.createStatement().use { it.execute("UPDATE member SET member_status = 'SUSPENDED' WHERE id = ${suspended.id}") } }

                    shouldThrow<BusinessException> {
                        reviewService.createReview(Long.MAX_VALUE, food.id, 5, null, null, "없는 회원이 쓴 리뷰입니다", null, null)
                    }.errorCode shouldBe ErrorCode.MEMBER_NOT_FOUND
                    shouldThrow<BusinessException> {
                        reviewService.createReview(suspended.id, food.id, 5, null, null, "정지 회원이 쓴 리뷰입니다", null, null)
                    }.errorCode shouldBe ErrorCode.MEMBER_NOT_FOUND

                    count("SELECT COUNT(*) FROM food_review") shouldBe 0
                    count("SELECT review_count FROM food WHERE id = ${food.id}") shouldBe 0
                    count("SELECT review_count FROM member WHERE id = ${suspended.id}") shouldBe 0
                }
            }
        }
    }
}
