package com.meogo.application.client.scan.dto

import com.meogo.core.scan.MenuItemMatch
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
        val matchStatus: String,
        val foodId: Long?,
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
                        matchStatus = statusOf(it.match),
                        foodId = foodIdOf(it.match),
                    )
                },
            )

        private fun statusOf(match: MenuItemMatch): String =
            when (match) {
                is MenuItemMatch.Matched -> "MATCHED"
                is MenuItemMatch.Pending -> "PENDING"
                MenuItemMatch.NotFood -> "NOT_FOOD"
            }

        private fun foodIdOf(match: MenuItemMatch): Long? =
            when (match) {
                is MenuItemMatch.Matched -> match.foodId
                is MenuItemMatch.Pending -> match.foodId
                MenuItemMatch.NotFood -> null
            }
    }
}
