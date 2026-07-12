package com.meogo.application.client.member.dto

import com.meogo.core.member.Ranking

data class MemberRankingResult(
    val tier: String,
    val level: Int,
    val score: Int,
    val nextTier: String?,
    val pointsToNext: Int?,
    val reviewCount: Int,
    val reviewPoints: Int,
    val uniqueReviewedFoodCount: Int,
    val diversityPoints: Int,
    val scanCount: Int,
    val scanPoints: Int,
) {
    companion object {
        fun from(ranking: Ranking): MemberRankingResult =
            MemberRankingResult(
                tier = ranking.tier.key,
                level = ranking.tier.level,
                score = ranking.score,
                nextTier = ranking.nextTier?.key,
                pointsToNext = ranking.pointsToNext,
                reviewCount = ranking.reviewCount,
                reviewPoints = ranking.reviewPoints,
                uniqueReviewedFoodCount = ranking.uniqueReviewedFoodCount,
                diversityPoints = ranking.diversityPoints,
                scanCount = ranking.scanCount,
                scanPoints = ranking.scanPoints,
            )
    }
}
