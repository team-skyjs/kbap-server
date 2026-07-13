package com.meogo.application.food.dto

import com.meogo.domain.food.AvoidanceSubstanceCodeRef
import com.meogo.domain.food.Food
import com.meogo.core.lang.LanguageCode
import com.meogo.core.risk.RiskLevel

data class FoodSummaryView(
    val foodId: Long,
    val name: String,
    val koreanName: String?,
    val imageRef: String?,
    val spiciness: Int,
    val overallRiskStatus: RiskLevel,
) {
    companion object {
        fun from(food: Food, lang: LanguageCode, userAvoidedCodes: Set<AvoidanceSubstanceCodeRef>): FoodSummaryView {
            val localizedName = food.displayName(lang)
            return FoodSummaryView(
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
