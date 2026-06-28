package com.meogo.api.application.food.dto

import com.meogo.api.core.risk.RiskLevel

data class GetFoodDetailResult(
    val name: String,
    val imageRef: String?,
    val ingredients: List<IngredientView>,
) {
    data class IngredientView(
        val name: String,
        val iconRef: String?,
        val inclusionPercent: Int,
        val riskStatus: RiskLevel,
    )
}
