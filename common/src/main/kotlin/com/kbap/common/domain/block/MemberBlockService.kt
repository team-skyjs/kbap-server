package com.kbap.common.domain.block

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.MemberService
import com.kbap.common.domain.member.model.Member
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MemberBlockService(
    private val memberBlockRepository: MemberBlockJpaRepository,
    private val memberService: MemberService,
    private val memberRepository: MemberJpaRepository,
) {
    @Transactional
    fun block(blockerMemberId: Long, targetMemberId: Long) {
        if (blockerMemberId == targetMemberId) {
            throw BusinessException(ErrorCode.SELF_BLOCK_FORBIDDEN)
        }
        memberService.getMemberOrNull(targetMemberId)
            ?: throw BusinessException(ErrorCode.BLOCK_TARGET_NOT_FOUND)

        memberBlockRepository.upsertActive(blockerMemberId, targetMemberId)
    }

    @Transactional
    fun unblock(blockerMemberId: Long, targetMemberId: Long) {
        memberBlockRepository.findByBlockerMemberIdAndBlockedMemberId(blockerMemberId, targetMemberId)?.delete()
    }

    @Transactional(readOnly = true)
    fun getBlockedMemberIds(memberId: Long): List<Long> =
        memberBlockRepository.findBlockedMemberIds(memberId)

    @Transactional(readOnly = true)
    fun getBlockedMembers(memberId: Long): List<Member> =
        memberRepository.findAllById(memberBlockRepository.findBlockedMemberIds(memberId))
}
