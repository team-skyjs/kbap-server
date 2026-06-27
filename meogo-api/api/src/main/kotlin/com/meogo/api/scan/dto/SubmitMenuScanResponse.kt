package com.meogo.api.scan.dto

import com.meogo.application.scan.MenuScanResult

data class SubmitMenuScanResponse(
    val scanId: Long,
    val results: List<ItemRiskResponse>,
) {
    data class ItemRiskResponse(
        val itemId: Int,
        val riskLevel: String,
        val reason: String,
    )

    companion object {
        fun from(result: MenuScanResult): SubmitMenuScanResponse =
            SubmitMenuScanResponse(
                scanId = result.scanId,
                results = result.results.map {
                    ItemRiskResponse(
                        itemId = it.itemId,
                        riskLevel = it.riskLevel.name,
                        reason = it.reason,
                    )
                },
            )
    }
}
