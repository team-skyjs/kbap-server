package com.meogo.api.persistence.food

import com.meogo.api.food.Food
import com.meogo.api.food.FoodRepository
import com.meogo.api.food.LanguageCode
import org.springframework.stereotype.Repository

@Repository
class FoodRepositoryAdapter(
    private val foodJpaRepository: FoodJpaRepository,
    private val foodNameTranslationJpaRepository: FoodNameTranslationJpaRepository,
    private val ingredientNameTranslationJpaRepository: IngredientNameTranslationJpaRepository,
) : FoodRepository {
    override fun findByKoreanName(name: String): Food? =
        foodJpaRepository.findByKoreanNameWithIngredients(name.trim())?.toDomain()

    override fun findFoodNameTranslation(foodId: Long, lang: LanguageCode): String? =
        foodNameTranslationJpaRepository.findByFoodIdAndLangCode(foodId, lang.code)?.name

    override fun findIngredientNameTranslations(ingredientIds: List<Long>, lang: LanguageCode): Map<Long, String> {
        if (ingredientIds.isEmpty()) return emptyMap()
        return ingredientNameTranslationJpaRepository
            .findByIngredientIdInAndLangCode(ingredientIds, lang.code)
            .associate { it.ingredientId to it.name }
    }
}
