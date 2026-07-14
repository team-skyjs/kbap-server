package com.kbap.app.api.food

import com.kbap.domain.food.dto.FoodSummaryView

data class FoodSummaryResponse(
    val foodId: Long,
    val name: String,
    val koreanName: String?,
    val imageRef: String?,
    val spiciness: Int,
    val overallRiskStatus: String,
) {
    companion object {
        fun from(view: FoodSummaryView) = FoodSummaryResponse(
            foodId = view.foodId,
            name = view.name,
            koreanName = view.koreanName,
            imageRef = view.imageRef,
            spiciness = view.spiciness,
            overallRiskStatus = view.overallRiskStatus.name,
        )
    }
}
