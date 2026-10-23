package com.kbap.api.review

data class RatingSummary(
    val averageRating: Double?,
    val reviewCount: Long,
    val sameCountryAverageRating: Double?,
    val sameCountryReviewCount: Long,
    val overallAxes: RatingAxes = RatingAxes.NONE,
    val sameCountryAxes: RatingAxes = RatingAxes.NONE,
)

data class RatingAxes(
    val servingSpeedAverage: Double?,
    val servingSpeedCount: Long,
    val staffKindnessAverage: Double?,
    val staffKindnessCount: Long,
) {
    companion object {
        val NONE = RatingAxes(null, 0, null, 0)
    }
}
