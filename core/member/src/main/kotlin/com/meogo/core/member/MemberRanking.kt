package com.meogo.core.member

class MemberRanking private constructor(
    val reviewCount: Int,
    val uniqueReviewedFoodCount: Int,
    val scanCount: Int,
) {
    val reviewPoints: Int = reviewCount * REVIEW_POINTS
    val diversityPoints: Int = uniqueReviewedFoodCount * DIVERSITY_POINTS
    val scanPoints: Int = scanCount * SCAN_POINTS

    val score: Int = reviewPoints + diversityPoints + scanPoints

    val tier: RankingTier = RankingTier.of(score)

    val nextTier: RankingTier? = tier.next

    val pointsToNext: Int? = nextTier?.let { it.minScore - score }

    companion object {
        private const val REVIEW_POINTS = 10
        private const val DIVERSITY_POINTS = 5
        private const val SCAN_POINTS = 2

        fun of(reviewCount: Int, uniqueReviewedFoodCount: Int, scanCount: Int): MemberRanking {
            require(reviewCount >= 0 && uniqueReviewedFoodCount >= 0 && scanCount >= 0) {
                "랭킹 카운트는 음수일 수 없습니다"
            }
            return MemberRanking(reviewCount, uniqueReviewedFoodCount, scanCount)
        }
    }
}
