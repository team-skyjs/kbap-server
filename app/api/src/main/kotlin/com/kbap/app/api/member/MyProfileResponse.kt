package com.kbap.app.api.member

import com.kbap.domain.member.dto.MemberRankingResult
import com.kbap.domain.member.dto.MyProfileResult

data class MyProfileResponse(
    val memberId: Long,
    val provider: String,
    val nickname: String?,
    val avoidanceSubstanceCodes: List<String>,
    val countryCode: String?,
    val profileImageUrl: String?,
    val spicinessPreference: Int,
    val onboardingCompleted: Boolean,
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
                countryCode = result.countryCode,
                profileImageUrl = result.profileImageUrl,
                spicinessPreference = result.spicinessPreference,
                onboardingCompleted = result.onboardingCompleted,
                ranking = RankingSummary.from(result.ranking),
            )
    }
}
