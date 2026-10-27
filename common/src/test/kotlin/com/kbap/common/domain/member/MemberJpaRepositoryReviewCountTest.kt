package com.kbap.common.domain.member

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.SocialIdentity
import com.kbap.common.domain.member.model.SocialProvider
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest
@Import(MySqlContainerConfig::class)
class MemberJpaRepositoryReviewCountTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var memberRepository: MemberJpaRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    init {
        fun <T> inTransaction(block: () -> T): T = TransactionTemplate(transactionManager).execute { block() }!!

        fun newMember(): Member =
            memberRepository.save(Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "review-count-${System.nanoTime()}", null)))

        fun counts(memberId: Long): Pair<Int, Int> {
            val member = memberRepository.findById(memberId).orElseThrow()
            return member.reviewCount to member.uniqueReviewedFoodCount
        }

        given("리뷰 랭킹 카운트 원자 증감") {
            `when`("리뷰 수와 고유 음식 수를 각각 올리면") {
                then("1행씩 갱신되고 (1, 1) 이다") {
                    val member = newMember()
                    inTransaction { memberRepository.increaseReviewCount(member.id) } shouldBe 1
                    inTransaction { memberRepository.increaseUniqueReviewedFoodCount(member.id) } shouldBe 1
                    counts(member.id) shouldBe (1 to 1)
                }
            }
            `when`("리뷰 수만 내리면") {
                then("고유 음식 수는 그대로다") {
                    val member = newMember()
                    inTransaction { memberRepository.increaseReviewCount(member.id) }
                    inTransaction { memberRepository.increaseUniqueReviewedFoodCount(member.id) }
                    inTransaction { memberRepository.increaseReviewCount(member.id) }
                    inTransaction { memberRepository.decreaseReviewCount(member.id) } shouldBe 1
                    counts(member.id) shouldBe (1 to 1)
                }
            }
            `when`("카운트가 0 인 회원을 내리면") {
                then("하한 가드로 0행이고 0 이 유지된다 — 예외 여부는 호출자 몫") {
                    val member = newMember()
                    inTransaction { memberRepository.decreaseReviewCount(member.id) } shouldBe 0
                    inTransaction { memberRepository.decreaseUniqueReviewedFoodCount(member.id) } shouldBe 0
                    counts(member.id) shouldBe (0 to 0)
                }
            }
            `when`("존재하지 않는 회원을 올리면") {
                then("0행이다") {
                    inTransaction { memberRepository.increaseReviewCount(Long.MAX_VALUE) } shouldBe 0
                    inTransaction { memberRepository.increaseUniqueReviewedFoodCount(Long.MAX_VALUE) } shouldBe 0
                }
            }
        }
    }
}
