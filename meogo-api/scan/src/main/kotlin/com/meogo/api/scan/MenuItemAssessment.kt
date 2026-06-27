package com.meogo.api.scan

import com.meogo.api.core.risk.RiskLevel

data class MenuItemAssessment(
    val riskLevel: RiskLevel,
    val reason: String,
)
