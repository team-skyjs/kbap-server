package com.meogo.application.client.food.dto

import com.meogo.core.kernel.risk.RiskLevel

data class GetFoodDetailResult(
    val name: String,
    val imageRef: String?,
    val description: String,
    val spiciness: Int,
    val avoidanceSubstances: List<AvoidanceSubstanceView>,
) {
    data class AvoidanceSubstanceView(
        val name: String,
        val iconRef: String?,
        val inclusionProbability: Int,
        val riskStatus: RiskLevel,
    )
}
