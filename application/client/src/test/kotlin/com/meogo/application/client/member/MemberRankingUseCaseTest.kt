package com.meogo.application.client.member

import com.meogo.domain.member.Member
import com.meogo.domain.member.MemberErrorCode
import com.meogo.domain.member.MemberException
import com.meogo.domain.member.MemberProfile
import com.meogo.domain.member.MemberRepository
import com.meogo.domain.member.Ranking
import com.meogo.domain.member.SocialIdentity
import com.meogo.domain.member.SocialProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class MemberRankingUseCaseTest : BehaviorSpec({

    fun member(id: Long, scanCount: Int = 0): Member = Member.reconstitute(
        id = id,
        identity = SocialIdentity(SocialProvider.GOOGLE, "google-sub-$id", "user$id@gmail.com"),
        profile = MemberProfile.empty(),
        onboardingCompleted = true,
        ranking = Ranking.of(scanCount = scanCount, reviewCount = 0, uniqueReviewedFoodCount = 0),
    )

    fun fixture(scanCount: Int = 0, seedMember: Boolean = true): MemberRankingUseCase {
        val memberRepository = FakeMemberRepository()
        if (seedMember) memberRepository.seed(member(11L, scanCount))
        return MemberRankingUseCase(memberRepository)
    }

    given("회원 랭킹 조회") {
        `when`("스캔을 9회 한 회원이면") {
            then("스캔 점수만 반영된 랭킹을 돌려준다") {
                val useCase = fixture(scanCount = 9)

                val ranking = useCase.getRanking(11L)

                ranking.scanCount shouldBe 9
                ranking.score shouldBe 18
                ranking.tier shouldBe "newcomer"
            }
        }

        `when`("리뷰 도메인이 아직 없는 동안") {
            then("리뷰 수·고유 음식 수는 0으로 산정된다") {
                val useCase = fixture(scanCount = 3)

                val ranking = useCase.getRanking(11L)

                ranking.reviewCount shouldBe 0
                ranking.uniqueReviewedFoodCount shouldBe 0
                ranking.reviewPoints shouldBe 0
                ranking.diversityPoints shouldBe 0
            }
        }

        `when`("스캔 이력이 전혀 없으면") {
            then("0점 최하 등급을 돌려준다") {
                val useCase = fixture(scanCount = 0)

                val ranking = useCase.getRanking(11L)

                ranking.score shouldBe 0
                ranking.tier shouldBe "newcomer"
                ranking.pointsToNext shouldBe 30
            }
        }

        `when`("존재하지 않는 회원이면") {
            then("회원을 찾을 수 없다는 예외를 던진다") {
                val useCase = fixture(seedMember = false)

                val exception = shouldThrow<MemberException> { useCase.getRanking(11L) }

                exception.errorCode shouldBe MemberErrorCode.MEMBER_NOT_FOUND
            }
        }
    }
})
