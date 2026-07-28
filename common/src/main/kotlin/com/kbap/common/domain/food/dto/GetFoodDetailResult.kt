package com.kbap.common.domain.food.dto

import com.kbap.common.domain.food.model.RiskLevel

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
