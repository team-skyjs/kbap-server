package com.kbap.api.food

import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.model.RiskLevel

data class FoodSummaryView(
    val foodId: Long,
    val name: String,
    val koreanName: String?,
    val imageRef: String?,
    val spiciness: Int,
    val overallRiskStatus: RiskLevel,
) {
    companion object {
        fun from(food: Food, lang: LanguageCode, userAvoidedCodes: Set<String>, imageUrl: String?): FoodSummaryView {
            val localizedName = food.displayName(lang)
            return FoodSummaryView(
                foodId = food.id,
                name = localizedName,
                koreanName = food.displayName(LanguageCode.KO).takeIf { it != localizedName },
                imageRef = imageUrl,
                spiciness = food.spiciness,
                overallRiskStatus = food.overallRisk(userAvoidedCodes),
            )
        }
    }
}
