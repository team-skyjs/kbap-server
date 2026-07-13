package com.meogo.application.client.member

import com.meogo.application.client.member.dto.MemberRankingResult
import com.meogo.domain.member.MemberErrorCode
import com.meogo.domain.member.MemberException
import com.meogo.domain.member.MemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MemberRankingUseCase(
    private val memberRepository: MemberRepository,
) {
    @Transactional(readOnly = true)
    fun getRanking(memberId: Long): MemberRankingResult {
        val member = memberRepository.findById(memberId)
            ?: throw MemberException(MemberErrorCode.MEMBER_NOT_FOUND)

        return MemberRankingResult.from(member.ranking)
    }
}
