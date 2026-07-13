package com.kbap.application.member

import com.kbap.application.member.dto.MemberRankingResult
import com.kbap.domain.member.MemberErrorCode
import com.kbap.domain.member.MemberException
import com.kbap.domain.member.MemberService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MemberRankingUseCase(
    private val memberService: MemberService,
) {
    @Transactional(readOnly = true)
    fun getRanking(memberId: Long): MemberRankingResult {
        val member = memberService.findById(memberId)
            ?: throw MemberException(MemberErrorCode.MEMBER_NOT_FOUND)

        return MemberRankingResult.from(member.ranking)
    }
}
