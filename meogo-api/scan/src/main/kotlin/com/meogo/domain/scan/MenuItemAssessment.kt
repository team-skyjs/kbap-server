package com.meogo.domain.scan

import com.meogo.core.risk.RiskLevel

data class MenuItemAssessment(
    val riskLevel: RiskLevel,
    val reason: String,
)
