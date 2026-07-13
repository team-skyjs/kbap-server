package com.meogo.app.api.member

import com.meogo.application.member.dto.MemberRankingResult
import com.meogo.application.member.dto.MyProfileResult

data class MyProfileResponse(
    val memberId: Long,
    val nickname: String?,
    val avoidanceSubstanceCodes: List<String>,
    val countryCode: String?,
    val appLanguage: String?,
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
                nickname = result.nickname,
                avoidanceSubstanceCodes = result.avoidanceSubstanceCodes,
                countryCode = result.countryCode,
                appLanguage = result.appLanguage,
                onboardingCompleted = result.onboardingCompleted,
                ranking = RankingSummary.from(result.ranking),
            )
    }
}
