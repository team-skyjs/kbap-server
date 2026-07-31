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

    // 차단 대상의 스냅샷을 저장하지 않으므로 목록은 조회 시점의 활성 회원만 최신 값으로 로드한다(탈퇴자 자연 제외)
    @Transactional(readOnly = true)
    fun getBlockedMembers(memberId: Long): List<Member> =
        memberRepository.findAllById(memberBlockRepository.findBlockedMemberIds(memberId))
}
