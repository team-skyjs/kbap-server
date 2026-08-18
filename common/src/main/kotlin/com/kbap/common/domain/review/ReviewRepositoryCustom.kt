package com.kbap.common.domain.review

import com.kbap.common.domain.review.model.Review

data class ReviewPageRow(
    val review: Review,
    val metric: Long,
)

interface ReviewRepositoryCustom {
    fun findReviewPage(
        foodId: Long?,
        countryCode: String?,
        minRating: Int?,
        maxRating: Int?,
        sort: ReviewSort,
        metricCursor: Long?,
        idCursor: Long?,
        excludedMemberIds: List<Long>,
        excludedReviewIds: List<Long>,
        limit: Int,
    ): List<ReviewPageRow>
}
