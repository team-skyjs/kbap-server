package com.meogo.core.food

import com.meogo.core.kernel.lang.LanguageCode

interface FoodRepository {
    fun findByKoreanName(name: String): Food?

    fun findFoodNameTranslation(foodId: Long, lang: LanguageCode): String?

    fun findFoodDescriptionTranslation(foodId: Long, lang: LanguageCode): String?
}
