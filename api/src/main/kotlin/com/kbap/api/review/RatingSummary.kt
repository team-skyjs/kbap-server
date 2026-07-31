package com.kbap.api.review

data class RatingSummary(
    val averageRating: Double?,
    val reviewCount: Long,
    val sameCountryAverageRating: Double?,
    val sameCountryReviewCount: Long,
)
