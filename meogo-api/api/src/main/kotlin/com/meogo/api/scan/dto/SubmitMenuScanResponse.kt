package com.meogo.api.scan.dto

import com.meogo.application.scan.MenuScanResult

/**
 * POST /menu-scans 응답 페이로드(ApiResponse.data). riskLevel 은 enum 이름 문자열로 노출한다.
 */
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
