package com.kbap.api.member

import com.kbap.common.domain.member.model.Member

data class AdRewardResult(
    val rewardCount: Int,
    val remainingForUnlock: Int,
    val scanUnlocked: Boolean,
) {
    companion object {
        fun of(rewardCount: Long, scanUnlocked: Boolean): AdRewardResult =
            AdRewardResult(
                rewardCount = rewardCount.toInt(),
                remainingForUnlock = Member.adRewardsRemainingForUnlock(rewardCount, scanUnlocked),
                scanUnlocked = scanUnlocked,
            )
    }
}
