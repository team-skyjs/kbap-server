package com.kbap.api.food

import com.kbap.api.review.FoodRating

data class FoodSummaryResponse(
    val foodId: Long,
    val name: String,
    val koreanName: String?,
    val imageRef: String?,
    val spiciness: Int,
    val overallRiskStatus: String,
    val bookmarked: Boolean,
    val review: ReviewInfoResponse,
) {
    data class ReviewInfoResponse(
        val averageRating: Double,
        val count: Long,
    )

    companion object {
        fun from(view: FoodSummaryView, bookmarked: Boolean, rating: FoodRating?) = FoodSummaryResponse(
            foodId = view.foodId,
            name = view.name,
            koreanName = view.koreanName,
            imageRef = view.imageRef,
            spiciness = view.spiciness,
            overallRiskStatus = view.overallRiskStatus.name,
            bookmarked = bookmarked,
            review = ReviewInfoResponse(
                averageRating = rating?.averageRating ?: 0.0,
                count = rating?.reviewCount ?: 0,
            ),
        )
    }
}
