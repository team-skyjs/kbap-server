package com.meogo.api.food

data class FoodIngredient(
    val id: Long? = null,
    val ingredient: Ingredient,
    val inclusionPercent: Int,
    val displayOrder: Int,
) {
    init {
        require(inclusionPercent in 0..100) {
            "foodIngredient.inclusionPercent 는 0~100 이어야 합니다 (inclusionPercent=$inclusionPercent)"
        }
    }
}
