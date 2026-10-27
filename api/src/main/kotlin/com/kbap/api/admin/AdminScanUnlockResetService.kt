package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.member.MemberAdRewardJpaRepository
import com.kbap.common.domain.member.MemberJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminScanUnlockResetService(
    private val memberRepository: MemberJpaRepository,
    private val adRewardRepository: MemberAdRewardJpaRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun resetScanUnlock(adminId: Long, memberId: Long): AdminScanUnlockResetResponse {
        val removedRewards = adRewardRepository.deleteAllByMemberId(memberId)
        val member = memberRepository.findByIdForUpdate(memberId) ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        if (member.isBot) throw BusinessException(ErrorCode.INVALID_REQUEST)
        member.resetScanUnlock()
        log.info("관리자 스캔 무제한 해제 취소 adminId={} memberId={} 광고적립삭제={} scanCount={}", adminId, member.id, removedRewards, member.scanCount)
        return AdminScanUnlockResetResponse(
            scanUnlocked = member.scanUnlocked,
            adRewardCount = 0,
            scanCount = member.scanCount,
            scanRemaining = member.scanRemaining() ?: 0,
        )
    }
}
