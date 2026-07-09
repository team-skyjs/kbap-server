package com.meogo.application.client.scan.dto

data class MenuScanResult(
    val items: List<ItemRiskResult>,
    val degraded: Boolean,
) {
    data class ItemRiskResult(
        val idx: Int,
        val riskLevel: String,
        val matched: Boolean,
        val foodId: Long?,
        val name: String?,
        val koreanName: String?,
    )
}
