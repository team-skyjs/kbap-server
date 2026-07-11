package com.meogo.application.client.member

import com.meogo.application.client.member.dto.MyProfileResult
import com.meogo.core.member.MemberErrorCode
import com.meogo.core.member.MemberException
import com.meogo.core.member.MemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GetMyProfileUseCase(
    private val memberRepository: MemberRepository,
) {
    @Transactional(readOnly = true)
    fun getMyProfile(memberId: Long): MyProfileResult {
        val member = memberRepository.findById(memberId)
            ?: throw MemberException(MemberErrorCode.MEMBER_NOT_FOUND)
        return MyProfileResult.from(member)
    }
}
