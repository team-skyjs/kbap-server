package com.kbap.common.domain.food.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.kbap.common.domain.food.model.RiskLevel

data class FoodIngredient(
    val code: String,
    @JsonProperty("inclusion_percent") val inclusionPercent: Int,
) {
    fun riskLevel(): RiskLevel = RiskLevel.fromInclusionProbability(inclusionPercent)
}
