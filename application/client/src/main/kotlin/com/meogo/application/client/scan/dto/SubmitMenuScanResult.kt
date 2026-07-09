package com.meogo.application.client.scan.dto

data class SubmitMenuScanResult(
    val items: List<ItemRiskResult>,
) {
    data class ItemRiskResult(
        val itemId: Int,
        val riskLevel: String,
        val matchStatus: String,
        val foodId: Long?,
    )
}
