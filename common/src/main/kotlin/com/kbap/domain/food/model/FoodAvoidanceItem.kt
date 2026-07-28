package com.kbap.domain.food.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.kbap.core.risk.RiskLevel

data class FoodAvoidanceItem(
    val code: String,
    @JsonProperty("inclusion_percent") val inclusionPercent: Int,
) {
    fun riskLevel(): RiskLevel = RiskLevel.fromInclusionProbability(inclusionPercent)
}
