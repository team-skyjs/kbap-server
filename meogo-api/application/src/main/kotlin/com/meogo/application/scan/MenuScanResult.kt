package com.meogo.application.scan

import com.meogo.core.risk.RiskLevel

data class MenuScanResult(
    val scanId: Long,
    val results: List<ItemResult>,
) {
    data class ItemResult(
        val itemId: Int,
        val riskLevel: RiskLevel,
        val reason: String,
    )
}
