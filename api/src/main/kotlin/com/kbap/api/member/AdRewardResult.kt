package com.kbap.api.member

import com.kbap.common.domain.member.model.Member

data class AdRewardResult(
    val rewardCount: Int,
    val remainingForUnlock: Int,
    val scanUnlocked: Boolean,
) {
    companion object {
        fun of(member: Member, rewardCount: Long): AdRewardResult =
            AdRewardResult(
                rewardCount = rewardCount.toInt(),
                remainingForUnlock = (Member.AD_REWARDS_FOR_UNLOCK - rewardCount.toInt()).coerceAtLeast(0),
                scanUnlocked = member.scanUnlocked,
            )
    }
}
