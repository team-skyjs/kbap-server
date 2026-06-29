package com.meogo.application.food.dto

import com.meogo.core.kernel.risk.RiskLevel

data class GetFoodDetailResult(
    val name: String,
    val imageRef: String?,
    val briefDescription: String,
    val detailedDescription: String,
    val ingredients: List<IngredientView>,
) {
    data class IngredientView(
        val name: String,
        val iconRef: String?,
        val inclusionPercent: Int,
        val riskStatus: RiskLevel,
    )
}
