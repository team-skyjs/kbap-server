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
            `when`("리뷰 수와 고유 음식 수를 각각 증가시키면") {
                then("두 카운트가 1 씩 늘어난다") {
                    val member = newMember()
                    memberService.increaseReviewCount(member.id)
                    memberService.increaseUniqueReviewedFoodCount(member.id)
                    counts(member.id) shouldBe (1 to 1)
                }
            }
            `when`("리뷰 수만 증가시키면") {
                then("고유 음식 수는 그대로다") {
                    val member = newMember()
                    memberService.increaseReviewCount(member.id)
                    memberService.increaseUniqueReviewedFoodCount(member.id)
                    memberService.increaseReviewCount(member.id)
                    counts(member.id) shouldBe (2 to 1)
                }
            }
            `when`("리뷰 수만 감소시키면") {
                then("고유 음식 수는 그대로다") {
                    val member = newMember()
                    memberService.increaseReviewCount(member.id)
                    memberService.increaseUniqueReviewedFoodCount(member.id)
                    memberService.increaseReviewCount(member.id)
                    memberService.decreaseReviewCount(member.id)
                    counts(member.id) shouldBe (1 to 1)
                }
            }
            `when`("두 카운트를 모두 감소시키면") {
                then("0·0 이 된다") {
                    val member = newMember()
                    memberService.increaseReviewCount(member.id)
                    memberService.increaseUniqueReviewedFoodCount(member.id)
                    memberService.decreaseReviewCount(member.id)
                    memberService.decreaseUniqueReviewedFoodCount(member.id)
                    counts(member.id) shouldBe (0 to 0)
                }
            }
            `when`("카운트가 0 인 회원을 감소시키면") {
                then("하한 가드로 MEMBER_NOT_FOUND 를 던지고 0 이 유지된다") {
                    val member = newMember()
                    shouldThrow<BusinessException> {
                        memberService.decreaseReviewCount(member.id)
                    }.errorCode shouldBe ErrorCode.MEMBER_NOT_FOUND
                    shouldThrow<BusinessException> {
                        memberService.decreaseUniqueReviewedFoodCount(member.id)
                    }.errorCode shouldBe ErrorCode.MEMBER_NOT_FOUND
                    counts(member.id) shouldBe (0 to 0)
                }
            }
            `when`("존재하지 않는 회원을 증가시키면") {
                then("MEMBER_NOT_FOUND 를 던진다") {
                    shouldThrow<BusinessException> {
                        memberService.increaseReviewCount(Long.MAX_VALUE)
                    }.errorCode shouldBe ErrorCode.MEMBER_NOT_FOUND
                    shouldThrow<BusinessException> {
                        memberService.increaseUniqueReviewedFoodCount(Long.MAX_VALUE)
                    }.errorCode shouldBe ErrorCode.MEMBER_NOT_FOUND
                }
            }
        }
    }
}
