package com.meogo.application.client.food.dto

import com.meogo.core.kernel.risk.RiskLevel

data class MenuSummaryView(
    val foodId: Long,
    val name: String,
    val koreanName: String?,
    val imageRef: String?,
    val spiciness: Int,
    val overallRiskStatus: RiskLevel,
)
