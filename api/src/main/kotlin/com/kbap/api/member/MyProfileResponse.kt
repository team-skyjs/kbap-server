package com.kbap.api.member


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
    val scanCount: Int,
    val freeScanLimit: Int,
    val scanUnlocked: Boolean,
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
