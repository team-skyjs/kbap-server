package com.meogo.infra.persistence.food

import com.meogo.core.food.Food
import com.meogo.core.food.FoodRepository
import com.meogo.core.kernel.lang.LanguageCode
import org.springframework.stereotype.Repository

@Repository
class FoodRepositoryAdapter(
    private val foodJpaRepository: FoodJpaRepository,
    private val foodNameTranslationJpaRepository: FoodNameTranslationJpaRepository,
    private val foodDescriptionTranslationJpaRepository: FoodDescriptionTranslationJpaRepository,
) : FoodRepository {
    override fun findByKoreanName(name: String): Food? =
        foodJpaRepository.findByKoreanNameWithAvoidanceSubstances(name.trim())?.toDomain()

    override fun findFoodNameTranslation(foodId: Long, lang: LanguageCode): String? =
        foodNameTranslationJpaRepository.findByFoodIdAndLangCode(foodId, lang.code)?.name

    override fun findFoodDescriptionTranslation(foodId: Long, lang: LanguageCode): String? {
        if (lang == LanguageCode.KO) return null
        return foodDescriptionTranslationJpaRepository.findByFoodIdAndLangCode(foodId, lang.code)?.content
    }
}
