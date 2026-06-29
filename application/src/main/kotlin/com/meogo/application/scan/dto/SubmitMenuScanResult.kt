package com.meogo.application.scan.dto

import com.meogo.core.scan.MenuScan

data class SubmitMenuScanResult(
    val scanId: Long,
    val items: List<ItemRiskResult>,
) {
    data class ItemRiskResult(
        val id: Long,
        val itemId: Int,
        val riskLevel: String,
        val reason: String,
    )

    companion object {
        fun from(menuScan: MenuScan): SubmitMenuScanResult =
            SubmitMenuScanResult(
                scanId = requireNotNull(menuScan.id) { "저장된 스캔에 id 가 없습니다" },
                items = menuScan.items.map {
                    ItemRiskResult(
                        id = requireNotNull(it.id) { "저장된 스캔 항목에 id 가 없습니다" },
                        itemId = it.itemId,
                        riskLevel = it.assessment.riskLevel.name,
                        reason = it.assessment.reason,
                    )
                },
            )
    }
}
