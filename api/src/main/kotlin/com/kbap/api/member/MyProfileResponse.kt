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
    @field:Schema(description = "회원의 누적 주문 수(소프트삭제 제외). 주문 없으면 0", example = "3")
    val orderCount: Long,
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
                orderCount = result.orderCount,
                ranking = RankingSummary.from(result.ranking),
            )
    }
}
