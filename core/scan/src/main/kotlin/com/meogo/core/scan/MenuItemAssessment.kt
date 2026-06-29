package com.meogo.core.scan

import com.meogo.core.kernel.risk.RiskLevel

data class MenuItemAssessment(
    val riskLevel: RiskLevel,
    val reason: String,
)
