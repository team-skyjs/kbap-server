package com.meogo.app.api.food

import com.meogo.application.client.food.dto.BrowseMenusResult

data class MenuSummaryResponse(
    val foodId: Long,
    val name: String,
    val imageRef: String?,
    val spiciness: Int,
    val overallRiskStatus: String,
) {
    companion object {
        fun from(view: BrowseMenusResult.MenuSummaryView) = MenuSummaryResponse(
            foodId = view.foodId,
            name = view.name,
            imageRef = view.imageRef,
            spiciness = view.spiciness,
            overallRiskStatus = view.overallRiskStatus.name,
        )
    }
}
