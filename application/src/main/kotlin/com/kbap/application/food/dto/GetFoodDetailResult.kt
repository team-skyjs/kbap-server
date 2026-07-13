package com.kbap.application.food.dto

import com.kbap.core.risk.RiskLevel

data class GetFoodDetailResult(
    val name: String,
    val koreanName: String?,
    val imageRef: String?,
    val description: String,
    val spiciness: Int,
    val overallRiskStatus: RiskLevel,
    val avoidanceSubstances: List<AvoidanceSubstanceView>,
) {
    data class AvoidanceSubstanceView(
        val name: String,
        val iconRef: String?,
        val inclusionProbability: Int,
        val riskStatus: RiskLevel,
    )
}
