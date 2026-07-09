package com.meogo.application.client.food.dto

import com.meogo.core.food.AvoidanceSubstanceCodeRef
import com.meogo.core.food.Food
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.risk.RiskLevel

data class MenuSummaryView(
    val foodId: Long,
    val name: String,
    val koreanName: String?,
    val imageRef: String?,
    val spiciness: Int,
    val overallRiskStatus: RiskLevel,
) {
    companion object {
        fun from(food: Food, lang: LanguageCode, userAvoidedCodes: Set<AvoidanceSubstanceCodeRef>): MenuSummaryView {
            val localizedName = food.displayName(lang)
            return MenuSummaryView(
                foodId = food.id!!,
                name = localizedName,
                koreanName = food.koreanName().takeIf { it != localizedName },
                imageRef = food.imageRef,
                spiciness = food.spiciness.value,
                overallRiskStatus = food.overallRisk(userAvoidedCodes),
            )
        }
    }
}
