package com.kbap.api.food

import com.kbap.api.review.FoodRating
import com.kbap.common.domain.food.dto.FoodSummaryView

data class FoodSummaryResponse(
    val foodId: Long,
    val name: String,
    val koreanName: String?,
    val imageRef: String?,
    val spiciness: Int,
    val overallRiskStatus: String,
    val bookmarked: Boolean,
    val averageRating: Double?,
    val reviewCount: Long,
) {
    companion object {
        fun from(view: FoodSummaryView, bookmarked: Boolean, rating: FoodRating?) = FoodSummaryResponse(
            foodId = view.foodId,
            name = view.name,
            koreanName = view.koreanName,
            imageRef = view.imageRef,
            spiciness = view.spiciness,
            overallRiskStatus = view.overallRiskStatus.name,
            bookmarked = bookmarked,
            averageRating = rating?.averageRating,
            reviewCount = rating?.reviewCount ?: 0,
        )
    }
}
