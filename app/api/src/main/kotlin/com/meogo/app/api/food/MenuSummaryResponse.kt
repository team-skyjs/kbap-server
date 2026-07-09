package com.meogo.app.api.food

import com.meogo.application.client.food.dto.MenuPage
import com.meogo.application.client.food.dto.MenuSummaryView

data class MenuSummaryResponse(
    val foodId: Long,
    val name: String,
    val koreanName: String?,
    val imageRef: String?,
    val spiciness: Int,
    val overallRiskStatus: String,
) {
    companion object {
        fun from(view: MenuSummaryView) = MenuSummaryResponse(
            foodId = view.foodId,
            name = view.name,
            koreanName = view.koreanName,
            imageRef = view.imageRef,
            spiciness = view.spiciness,
            overallRiskStatus = view.overallRiskStatus.name,
        )
    }
}
