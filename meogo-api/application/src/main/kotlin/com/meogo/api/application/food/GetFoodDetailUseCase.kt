package com.meogo.api.application.food

import com.meogo.api.food.FoodRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GetFoodDetailUseCase(
    private val foodRepository: FoodRepository,
    private val languageResolver: LanguageResolver,
    private val ingredientRiskMarker: IngredientRiskMarker,
) {
    @Transactional(readOnly = true)
    fun getDetail(input: GetFoodDetailInput): GetFoodDetailResult {
        val lang = languageResolver.resolve(input.lang)
        val food = foodRepository.findByKoreanName(input.menuName.trim())
            ?: throw IllegalArgumentException("해당 음식 정보 없음")

        val risks = ingredientRiskMarker.mark(food.ingredients.map { it.ingredient })
        val ingredients = food.ingredients.mapIndexed { index, foodIngredient ->
            GetFoodDetailResult.IngredientView(
                name = foodIngredient.ingredient.nameFor(lang),
                iconRef = foodIngredient.ingredient.iconRef,
                inclusionPercent = foodIngredient.inclusionPercent,
                riskStatus = risks[index],
            )
        }

        return GetFoodDetailResult(
            name = food.nameFor(lang),
            imageRef = food.imageRef,
            ingredients = ingredients,
        )
    }
}
