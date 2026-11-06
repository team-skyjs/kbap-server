package com.kbap.api.food

import com.fasterxml.jackson.annotation.JsonInclude
import com.kbap.api.review.FoodRating
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class FoodSummaryResponse(
    val foodId: Long,
    val name: String,
    val koreanName: String?,
    val imageRef: String?,
    val spiciness: Int,
    val overallRiskStatus: String,
    val publishedAt: Instant?,
    val bookmarked: Boolean,
    val review: ReviewInfoResponse,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:Schema(description = "연결된 가공식품 대표 상품(S-968·S-973, 콜라·사이다 같은 음료) — 연결이 없으면 필드가 빠진다. 앱은 imageUrl 을 imageRef 보다 먼저 보여 준다", nullable = true)
    val linkedProcessedFood: LinkedProcessedFoodResponse? = null,
) {
    data class ReviewInfoResponse(
        val averageRating: Double,
        val count: Long,
    )

    companion object {
        fun from(view: FoodSummaryView, bookmarked: Boolean, rating: FoodRating?, linked: LinkedProcessedFoodResponse? = null) = FoodSummaryResponse(
            foodId = view.foodId,
            name = view.name,
            koreanName = view.koreanName,
            imageRef = view.imageRef,
            spiciness = view.spiciness,
            overallRiskStatus = view.overallRiskStatus.name,
            publishedAt = view.publishedAt,
            bookmarked = bookmarked,
            review = ReviewInfoResponse(
                averageRating = rating?.averageRating ?: 0.0,
                count = rating?.reviewCount ?: 0,
            ),
            linkedProcessedFood = linked,
        )
    }
}
