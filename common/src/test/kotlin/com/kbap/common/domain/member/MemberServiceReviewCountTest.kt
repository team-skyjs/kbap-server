package com.kbap.common.domain.member

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.SocialIdentity
import com.kbap.common.domain.member.model.SocialProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest(classes = [MemberServiceTestApp::class])
@Import(MySqlContainerConfig::class)
class MemberServiceReviewCountTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var memberService: MemberService

    @Autowired
    private lateinit var memberRepository: MemberJpaRepository

    init {
        fun newMember(): Member =
            memberRepository.save(
                Member.signUp(
                    SocialIdentity(SocialProvider.GOOGLE, "review-count-${System.nanoTime()}", null),
                ),
            )

        fun counts(memberId: Long): Pair<Int, Int> {
            val member = memberRepository.findById(memberId).orElseThrow()
            return member.reviewCount to member.uniqueReviewedFoodCount
        }

        given("리뷰 랭킹 카운트 증감") {
            `when`("첫 리뷰로 증가시키면") {
                then("리뷰 수 1·고유 음식 수 1 이 된다") {
                    val member = newMember()
                    memberService.increaseReviewCounts(member.id, firstReviewOfFood = true)
                    counts(member.id) shouldBe (1 to 1)
                }
            }
            `when`("같은 음식 추가 리뷰로 증가시키면") {
                then("리뷰 수만 늘고 고유 음식 수는 그대로다") {
                    val member = newMember()
                    memberService.increaseReviewCounts(member.id, firstReviewOfFood = true)
                    memberService.increaseReviewCounts(member.id, firstReviewOfFood = false)
                    counts(member.id) shouldBe (2 to 1)
                }
            }
            `when`("마지막이 아닌 리뷰를 감소시키면") {
                then("리뷰 수만 줄고 고유 음식 수는 그대로다") {
                    val member = newMember()
                    memberService.increaseReviewCounts(member.id, firstReviewOfFood = true)
                    memberService.increaseReviewCounts(member.id, firstReviewOfFood = false)
                    memberService.decreaseReviewCounts(member.id, lastReviewOfFood = false)
                    counts(member.id) shouldBe (1 to 1)
                }
            }
            `when`("마지막 리뷰를 감소시키면") {
                then("리뷰 수 0·고유 음식 수 0 이 된다") {
                    val member = newMember()
                    memberService.increaseReviewCounts(member.id, firstReviewOfFood = true)
                    memberService.decreaseReviewCounts(member.id, lastReviewOfFood = true)
                    counts(member.id) shouldBe (0 to 0)
                }
            }
            `when`("존재하지 않는 회원을 증가시키면") {
                then("MEMBER_NOT_FOUND 를 던진다") {
                    shouldThrow<BusinessException> {
                        memberService.increaseReviewCounts(Long.MAX_VALUE, firstReviewOfFood = true)
                    }.errorCode shouldBe ErrorCode.MEMBER_NOT_FOUND
                }
            }
            `when`("존재하지 않는 회원을 감소시키면") {
                then("MEMBER_NOT_FOUND 를 던진다") {
                    shouldThrow<BusinessException> {
                        memberService.decreaseReviewCounts(Long.MAX_VALUE, lastReviewOfFood = false)
                    }.errorCode shouldBe ErrorCode.MEMBER_NOT_FOUND
                }
            }
        }
    }
}
