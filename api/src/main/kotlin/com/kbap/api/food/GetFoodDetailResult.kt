package com.kbap.api.food

import com.kbap.common.domain.food.model.RiskLevel

data class GetFoodDetailResult(
    val name: String,
    val koreanName: String?,
    val imageRef: String?,
    val description: String,
    val spiciness: Int,
    val overallRiskStatus: RiskLevel,
    val ingredients: List<IngredientView>,
) {
    data class IngredientView(
        val name: String,
        val iconRef: String?,
        val inclusionProbability: Int,
        val riskStatus: RiskLevel,
    )
}
