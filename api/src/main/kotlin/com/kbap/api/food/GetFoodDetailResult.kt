package com.kbap.api.food

import com.kbap.common.domain.food.model.RiskLevel
import java.time.Instant

data class GetFoodDetailResult(
    val name: String,
    val koreanName: String?,
    val imageRef: String?,
    val description: String,
    val spiciness: Int,
    val overallRiskStatus: RiskLevel?,
    val ingredients: List<IngredientView>,
    val avoidedIngredients: List<AvoidedIngredientView>?,
    val reviewEligible: Boolean,
    val publishedAt: Instant?,
    val images: List<String>,
) {

    data class IngredientView(
        val code: String,
        val name: String,
        val inclusionPercent: Int,
    )

    data class AvoidedIngredientView(
        val code: String,
        val riskStatus: RiskLevel,
        val matchedBy: String?,
    )
}
