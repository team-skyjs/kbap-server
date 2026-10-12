package com.kbap.api.member

import io.swagger.v3.oas.annotations.media.Schema

data class AdRewardResponse(
    @field:Schema(description = "누적 적립 수(서버 정본)", example = "1")
    val rewardCount: Int,
    @field:Schema(description = "스캔 해제까지 남은 적립 수(0 이상). scanUnlocked=true 면(리뷰로 해제된 회원 포함) 항상 0. 해제 뒤에도 적립은 계속 센다", example = "2")
    val remainingForUnlock: Int,
    @field:Schema(description = "스캔 무제한 해제 여부 — 프로필의 scanUnlocked 와 같은 정본", example = "false")
    val scanUnlocked: Boolean,
) {
    companion object {
        fun from(result: AdRewardResult): AdRewardResponse =
            AdRewardResponse(rewardCount = result.rewardCount, remainingForUnlock = result.remainingForUnlock, scanUnlocked = result.scanUnlocked)
    }
}
