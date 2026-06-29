package com.meogo.core.food

import com.meogo.core.kernel.stereotype.AggregateRoot

@AggregateRoot
class Food private constructor(
    val id: Long?,
    val koreanName: String,
    val imageRef: String?,
    val briefDescription: String,
    val detailedDescription: String,
    val ingredients: List<FoodIngredient>,
) {
    init {
        require(koreanName.isNotBlank()) { "food.koreanName 은 blank 일 수 없습니다" }
        require(briefDescription.isNotBlank()) { "food.briefDescription 은 blank 일 수 없습니다" }
        require(briefDescription.length <= BRIEF_MAX_LENGTH) { "food.briefDescription 은 ${BRIEF_MAX_LENGTH}자를 초과할 수 없습니다" }
        require(detailedDescription.isNotBlank()) { "food.detailedDescription 은 blank 일 수 없습니다" }
        require(detailedDescription.length <= DETAILED_MAX_LENGTH) { "food.detailedDescription 은 ${DETAILED_MAX_LENGTH}자를 초과할 수 없습니다" }
    }

    fun ingredientsByInclusion(): List<FoodIngredient> =
        ingredients.sortedByDescending { it.inclusionPercent }

    companion object {
        const val BRIEF_MAX_LENGTH = 255
        const val DETAILED_MAX_LENGTH = 1024

        fun create(
            koreanName: String,
            imageRef: String? = null,
            briefDescription: String,
            detailedDescription: String,
            ingredients: List<FoodIngredient>,
        ): Food = Food(
            id = null,
            koreanName = koreanName,
            imageRef = imageRef,
            briefDescription = briefDescription,
            detailedDescription = detailedDescription,
            ingredients = ingredients,
        )

        fun reconstitute(
            id: Long,
            koreanName: String,
            imageRef: String?,
            briefDescription: String,
            detailedDescription: String,
            ingredients: List<FoodIngredient>,
        ): Food = Food(
            id = id,
            koreanName = koreanName,
            imageRef = imageRef,
            briefDescription = briefDescription,
            detailedDescription = detailedDescription,
            ingredients = ingredients,
        )
    }
}
