package com.kbap.api.member

import io.swagger.v3.oas.annotations.media.Schema

data class MyProfileResponse(
    val memberId: Long,
    val provider: String,
    val nickname: String?,
    val avoidanceSubstanceCodes: List<String>,
    val dietCategories: List<String>,
    val countryCode: String?,
    val profileImageUrl: String?,
    val spicinessPreference: String,
    val currency: String?,
    val onboardingCompleted: Boolean,
    @field:Schema(description = "누적 스캔 횟수", example = "1")
    val scanCount: Int,
    @field:Schema(description = "무료 스캔 한도(현재 3)", example = "3")
    val freeScanLimit: Int,
    @field:Schema(description = "스캔 무제한 해금 여부. 무제한 정본 = 이 값(true 면 scanRemaining=null)", example = "false")
    val scanUnlocked: Boolean,
    @field:Schema(description = "잔여 무료 스캔 횟수. scanUnlocked=true(무제한)면 null", example = "2", nullable = true)
    val scanRemaining: Int?,
    val ranking: RankingSummary,
) {
    data class RankingSummary(
        val tier: String,
        val level: Int,
        val score: Int,
        val nextTier: String?,
        val pointsToNext: Int?,
    ) {
        companion object {
            fun from(result: MemberRankingResult): RankingSummary =
                RankingSummary(
                    tier = result.tier,
                    level = result.level,
                    score = result.score,
                    nextTier = result.nextTier,
                    pointsToNext = result.pointsToNext,
                )
        }
    }

    companion object {
        fun from(result: MyProfileResult): MyProfileResponse =
            MyProfileResponse(
                memberId = result.memberId,
                provider = result.provider,
                nickname = result.nickname,
                avoidanceSubstanceCodes = result.avoidanceSubstanceCodes,
                dietCategories = result.dietCategories,
                countryCode = result.countryCode,
                profileImageUrl = result.profileImageUrl,
                spicinessPreference = result.spicinessPreference,
                currency = result.currency,
                onboardingCompleted = result.onboardingCompleted,
                scanCount = result.scanCount,
                freeScanLimit = result.freeScanLimit,
                scanUnlocked = result.scanUnlocked,
                scanRemaining = result.scanRemaining,
                ranking = RankingSummary.from(result.ranking),
            )
    }
}
