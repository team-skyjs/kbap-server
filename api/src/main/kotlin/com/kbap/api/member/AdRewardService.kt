package com.kbap.api.member

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.member.MemberAdRewardJpaRepository
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.MemberAdReward
import com.kbap.common.domain.member.model.MemberStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime

@Service
class AdRewardService(
    private val memberRepository: MemberJpaRepository,
    private val adRewardRepository: MemberAdRewardJpaRepository,
) {
    @Transactional
    fun claimAdReward(memberId: Long, rewardKey: String, adUnitId: String): AdRewardResult {
        val member = memberRepository.findByIdForUpdate(memberId)?.takeIf { it.memberStatus == MemberStatus.ACTIVE }
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        adRewardRepository.findByRewardKey(rewardKey)?.let { claimed ->
            if (claimed.memberId != member.id) throw BusinessException(ErrorCode.INVALID_REQUEST)
            return AdRewardResult.of(member, adRewardRepository.countByMemberId(member.id))
        }
        val since = LocalDateTime.now().minus(DAILY_WINDOW)
        if (adRewardRepository.countByMemberIdAndCreatedAtGreaterThanEqual(member.id, since) >= DAILY_LIMIT) {
            throw BusinessException(ErrorCode.AD_REWARD_DAILY_LIMIT)
        }
        adRewardRepository.save(MemberAdReward.of(member.id, rewardKey, adUnitId))
        val rewardCount = adRewardRepository.countByMemberId(member.id)
        member.unlockScanIfAdRewardsReached(rewardCount)
        return AdRewardResult.of(member, rewardCount)
    }

    companion object {
        const val DAILY_LIMIT: Int = 10
        val DAILY_WINDOW: Duration = Duration.ofHours(24)
    }
}
