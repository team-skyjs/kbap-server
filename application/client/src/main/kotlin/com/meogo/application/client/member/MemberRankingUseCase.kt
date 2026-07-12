package com.meogo.application.client.member

import com.meogo.application.client.member.dto.MemberRankingResult
import com.meogo.core.member.MemberErrorCode
import com.meogo.core.member.MemberException
import com.meogo.core.member.MemberRanking
import com.meogo.core.member.MemberRankingRepository
import com.meogo.core.member.MemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MemberRankingUseCase(
    private val memberRepository: MemberRepository,
    private val memberRankingRepository: MemberRankingRepository,
) {
    @Transactional(readOnly = true)
    fun getRanking(memberId: Long): MemberRankingResult {
        memberRepository.findById(memberId)
            ?: throw MemberException(MemberErrorCode.MEMBER_NOT_FOUND)

        val ranking = MemberRanking.of(
            reviewCount = REVIEW_DOMAIN_ABSENT,
            uniqueReviewedFoodCount = REVIEW_DOMAIN_ABSENT,
            scanCount = memberRankingRepository.scanCountOf(memberId),
        )
        return MemberRankingResult.from(ranking)
    }

    companion object {
        private const val REVIEW_DOMAIN_ABSENT = 0
    }
}
