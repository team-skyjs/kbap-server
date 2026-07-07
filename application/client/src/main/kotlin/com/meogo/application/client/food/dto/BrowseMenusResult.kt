package com.meogo.application.client.food.dto

import com.meogo.core.kernel.risk.RiskLevel

data class BrowseMenusResult(
    val items: List<MenuSummaryView>,
    val nextCursor: Long?,
    val hasNext: Boolean,
) {
    data class MenuSummaryView(
        val foodId: Long,
        val name: String,
        val koreanName: String?,
        val imageRef: String?,
        val spiciness: Int,
        val overallRiskStatus: RiskLevel,
    )
}
