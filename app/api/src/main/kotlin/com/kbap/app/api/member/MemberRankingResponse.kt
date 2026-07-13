package com.kbap.app.api.member

import com.kbap.domain.member.dto.MemberRankingResult

data class MemberRankingResponse(
    val tier: String,
    val level: Int,
    val score: Int,
    val nextTier: String?,
    val pointsToNext: Int?,
    val breakdown: Breakdown,
) {
    data class Breakdown(
        val reviews: Entry,
        val diversity: Entry,
        val scans: Entry,
    )

    data class Entry(
        val count: Int,
        val points: Int,
    )

    companion object {
        fun from(result: MemberRankingResult): MemberRankingResponse =
            MemberRankingResponse(
                tier = result.tier,
                level = result.level,
                score = result.score,
                nextTier = result.nextTier,
                pointsToNext = result.pointsToNext,
                breakdown = Breakdown(
                    reviews = Entry(result.reviewCount, result.reviewPoints),
                    diversity = Entry(result.uniqueReviewedFoodCount, result.diversityPoints),
                    scans = Entry(result.scanCount, result.scanPoints),
                ),
            )
    }
}
