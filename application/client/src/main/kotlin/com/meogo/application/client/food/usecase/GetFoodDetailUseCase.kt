package com.meogo.application.client.food.usecase

import com.meogo.application.client.food.dto.GetFoodDetailInput
import com.meogo.application.client.food.dto.GetFoodDetailResult
import com.meogo.core.kernel.risk.RiskLevel
import com.meogo.core.food.Food
import com.meogo.core.food.FoodDescriptionKind
import com.meogo.core.food.FoodRepository
import com.meogo.core.food.LanguageCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GetFoodDetailUseCase(
    private val foodRepository: FoodRepository,
    private val languageResolver: LanguageResolver,
    private val ingredientRiskMarker: MockIngredientRiskMarker,
) {
    @Transactional(readOnly = true)
    fun getDetail(input: GetFoodDetailInput): GetFoodDetailResult {
        val lang = languageResolver.resolve(input.lang)
        val food = foodRepository.findByKoreanName(input.menuName.trim())
            ?: throw IllegalArgumentException("해당 음식 정보 없음")

        val orderedIngredients = food.ingredientsByInclusion()

        val foodName = resolveFoodName(food.id, food.koreanName, lang)
        val ingredientNames = resolveIngredientNames(orderedIngredients.mapNotNull { it.ingredient.id }, lang)
        val descriptions = resolveDescriptions(food, lang)

        val risks = ingredientRiskMarker.mark(orderedIngredients.map { it.ingredient })
        val ingredients = orderedIngredients.map { foodIngredient ->
            val ingredient = foodIngredient.ingredient
            GetFoodDetailResult.IngredientView(
                name = ingredientNames[ingredient.id] ?: ingredient.koreanName,
                iconRef = ingredient.iconRef,
                inclusionPercent = foodIngredient.inclusionPercent,
                riskStatus = risks[ingredient.id] ?: RiskLevel.SAFE,
            )
        }

        return GetFoodDetailResult(
            name = foodName,
            imageRef = food.imageRef,
            briefDescription = descriptions[FoodDescriptionKind.BRIEF] ?: food.briefDescription,
            detailedDescription = descriptions[FoodDescriptionKind.DETAILED] ?: food.detailedDescription,
            ingredients = ingredients,
        )
    }

    private fun resolveFoodName(foodId: Long?, koreanName: String, lang: LanguageCode): String {
        if (lang == LanguageCode.KO || foodId == null) return koreanName
        return foodRepository.findFoodNameTranslation(foodId, lang) ?: koreanName
    }

    private fun resolveIngredientNames(ingredientIds: List<Long>, lang: LanguageCode): Map<Long, String> {
        if (lang == LanguageCode.KO) return emptyMap()
        return foodRepository.findIngredientNameTranslations(ingredientIds, lang)
    }

    private fun resolveDescriptions(food: Food, lang: LanguageCode): Map<FoodDescriptionKind, String> {
        val foodId = food.id
        if (lang == LanguageCode.KO || foodId == null) return emptyMap()
        return foodRepository.findFoodDescriptionTranslations(foodId, lang)
    }
}
