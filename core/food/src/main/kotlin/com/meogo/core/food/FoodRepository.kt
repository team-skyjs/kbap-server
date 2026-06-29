package com.meogo.core.food

import com.meogo.core.kernel.lang.LanguageCode

interface FoodRepository {
    fun findByKoreanName(name: String): Food?

    fun findFoodNameTranslation(foodId: Long, lang: LanguageCode): String?

    fun findIngredientNameTranslations(ingredientIds: List<Long>, lang: LanguageCode): Map<Long, String>

    fun findFoodDescriptionTranslations(foodId: Long, lang: LanguageCode): Map<FoodDescriptionKind, String>
}
