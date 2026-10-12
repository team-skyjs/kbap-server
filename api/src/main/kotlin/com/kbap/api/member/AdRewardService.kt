package com.kbap.api.member

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.member.MemberAdRewardJpaRepository
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberAdReward
import com.kbap.common.domain.member.model.MemberStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.LocalDateTime

@Service
class AdRewardService(
    private val memberRepository: MemberJpaRepository,
    private val adRewardRepository: MemberAdRewardJpaRepository,
    transactionManager: PlatformTransactionManager,
    @Value("\${kbap.ads.reward-unit-ids:$DEFAULT_REWARD_UNIT_IDS}") rewardUnitIds: List<String>,
) {
    private val transaction = TransactionTemplate(transactionManager)
    private val allowedAdUnitIds: Set<String> = rewardUnitIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun claimAdReward(memberId: Long, rewardKey: String, adUnitId: String): AdRewardResult {
        if (adUnitId !in allowedAdUnitIds) throw BusinessException(ErrorCode.INVALID_REQUEST)
        return try {
            transaction.execute { record(memberId, rewardKey, adUnitId) }!!
        } catch (e: DataIntegrityViolationException) {
            transaction.execute { alreadyClaimed(memberId, rewardKey) }!!
        }
    }

    private fun record(memberId: Long, rewardKey: String, adUnitId: String): AdRewardResult {
        val member = activeMember(memberId)
        adRewardRepository.findByRewardKey(rewardKey)?.let { return claimedResult(member, it) }
        val counts = adRewardRepository.countTotalAndRecent(member.id, LocalDateTime.now().minus(DAILY_WINDOW))
        if (counts.recent >= DAILY_LIMIT) throw BusinessException(ErrorCode.AD_REWARD_DAILY_LIMIT)
        adRewardRepository.saveAndFlush(MemberAdReward.of(member.id, rewardKey, adUnitId))
        val total = counts.total + 1
        val scanUnlocked = member.scanUnlocked || (Member.adRewardsUnlockScan(total) && memberRepository.unlockScan(member.id) == 1)
        return AdRewardResult.of(total, scanUnlocked)
    }

    private fun alreadyClaimed(memberId: Long, rewardKey: String): AdRewardResult {
        val member = activeMember(memberId)
        val claimed = adRewardRepository.findByRewardKey(rewardKey) ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        return claimedResult(member, claimed)
    }

    private fun claimedResult(member: Member, claimed: MemberAdReward): AdRewardResult {
        if (claimed.memberId != member.id) throw BusinessException(ErrorCode.INVALID_REQUEST)
        return AdRewardResult.of(adRewardRepository.countByMemberId(member.id), member.scanUnlocked)
    }

    private fun activeMember(memberId: Long): Member =
        memberRepository.findByIdOrNull(memberId)?.takeIf { it.memberStatus == MemberStatus.ACTIVE }
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)

    companion object {
        const val DAILY_LIMIT: Int = 10
        val DAILY_WINDOW: Duration = Duration.ofHours(24)
        const val ANDROID_DEMO_REWARD_UNIT_ID: String = "ca-app-pub-3940256099942544/5224354917"
        const val IOS_DEMO_REWARD_UNIT_ID: String = "ca-app-pub-3940256099942544/1712485313"
        const val DEFAULT_REWARD_UNIT_IDS: String = "$ANDROID_DEMO_REWARD_UNIT_ID,$IOS_DEMO_REWARD_UNIT_ID"
    }
}
