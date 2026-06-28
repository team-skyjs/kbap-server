package com.meogo.api.food

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodTest : BehaviorSpec({
    fun foodIngredient(koreanName: String, percent: Int) =
        FoodIngredient(ingredient = Ingredient(koreanName = koreanName), inclusionPercent = percent)

    given("Food.ingredientsByInclusion") {
        `when`("재료가 포함비율 내림차순이 아닌 순서로 담겨 있으면") {
            then("포함비율 내림차순으로 정렬된 재료를 반환한다(머리가 주성분)") {
                val food = Food(
                    koreanName = "된장찌개",
                    ingredients = listOf(
                        foodIngredient("두부", 90),
                        foodIngredient("된장", 100),
                        foodIngredient("바지락", 50),
                    ),
                )

                food.ingredientsByInclusion().map { it.inclusionPercent } shouldBe listOf(100, 90, 50)
                food.ingredientsByInclusion().first().ingredient.koreanName shouldBe "된장"
            }
        }
    }
})
