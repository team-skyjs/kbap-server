package com.kbap.common.domain.block

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.block.model.MemberBlock
import com.kbap.common.domain.member.MemberService
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MemberBlockService(
    private val memberBlockRepository: MemberBlockJpaRepository,
    private val memberService: MemberService,
) {
    @Transactional
    fun block(blockerMemberId: Long, targetMemberId: Long) {
        if (blockerMemberId == targetMemberId) {
            throw BusinessException(ErrorCode.SELF_BLOCK_FORBIDDEN)
        }
        memberService.getMemberOrNull(targetMemberId)
            ?: throw BusinessException(ErrorCode.BLOCK_TARGET_NOT_FOUND)

        val existing = memberBlockRepository.findAnyByPair(blockerMemberId, targetMemberId)
        if (existing != null) {
            existing.active()
            return
        }
        try {
            memberBlockRepository.save(MemberBlock(blockerMemberId = blockerMemberId, blockedMemberId = targetMemberId))
        } catch (e: DataIntegrityViolationException) {
            // 동시 차단 경합의 unique 위반 — 이미 차단됐다는 뜻이므로 멱등 성공으로 마감(최소 방어 컨벤션)
        }
    }

    @Transactional(readOnly = true)
    fun getBlockedMemberIds(memberId: Long): List<Long> =
        memberBlockRepository.findBlockedMemberIds(memberId)
}
