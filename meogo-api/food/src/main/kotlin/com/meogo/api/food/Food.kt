package com.meogo.api.food

import com.meogo.api.core.stereotype.AggregateRoot

@AggregateRoot
class Food private constructor(
    val id: Long?,
    val koreanName: String,
    val imageRef: String?,
    val ingredients: List<FoodIngredient>,
) {
    init {
        require(koreanName.isNotBlank()) { "food.koreanName 은 blank 일 수 없습니다" }
    }

    fun ingredientsByInclusion(): List<FoodIngredient> =
        ingredients.sortedByDescending { it.inclusionPercent }

    companion object {
        fun create(
            koreanName: String,
            imageRef: String? = null,
            ingredients: List<FoodIngredient>,
        ): Food = Food(id = null, koreanName = koreanName, imageRef = imageRef, ingredients = ingredients)

        fun reconstitute(
            id: Long,
            koreanName: String,
            imageRef: String?,
            ingredients: List<FoodIngredient>,
        ): Food = Food(id = id, koreanName = koreanName, imageRef = imageRef, ingredients = ingredients)
    }
}
