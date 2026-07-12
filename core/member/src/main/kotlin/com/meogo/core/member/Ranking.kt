package com.meogo.core.member

class Ranking private constructor(
    val scanCount: Int,
    val reviewCount: Int,
    val uniqueReviewedFoodCount: Int,
) {
    val reviewPoints: Int = reviewCount * REVIEW_POINTS
    val diversityPoints: Int = uniqueReviewedFoodCount * DIVERSITY_POINTS
    val scanPoints: Int = scanCount * SCAN_POINTS

    val score: Int = reviewPoints + diversityPoints + scanPoints

    val tier: RankingTier = RankingTier.of(score)

    val nextTier: RankingTier? = tier.next

    val pointsToNext: Int? = nextTier?.let { it.minScore - score }

    fun recordScan(): Ranking = of(scanCount + 1, reviewCount, uniqueReviewedFoodCount)

    companion object {
        private const val REVIEW_POINTS = 10
        private const val DIVERSITY_POINTS = 5
        private const val SCAN_POINTS = 2

        fun initial(): Ranking = of(scanCount = 0, reviewCount = 0, uniqueReviewedFoodCount = 0)

        fun of(scanCount: Int, reviewCount: Int, uniqueReviewedFoodCount: Int): Ranking {
            require(scanCount >= 0 && reviewCount >= 0 && uniqueReviewedFoodCount >= 0) {
                "랭킹 카운트는 음수일 수 없습니다"
            }
            return Ranking(scanCount, reviewCount, uniqueReviewedFoodCount)
        }
    }
}
