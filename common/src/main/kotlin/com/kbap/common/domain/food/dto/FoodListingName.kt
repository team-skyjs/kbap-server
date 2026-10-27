package com.kbap.common.domain.food.dto

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.model.FoodName
import java.time.LocalDateTime

data class FoodListingName(
    val id: Long,
    val koreanName: String,
    val displayName: String,
    val nameTranslations: Map<String, String>,
    val updatedAt: LocalDateTime,
) {
    fun name(lang: LanguageCode): String = FoodName.resolve(koreanName, displayName, nameTranslations, lang)
}
