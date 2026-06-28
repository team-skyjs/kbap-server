package com.meogo.api.food

data class Ingredient(
    val id: Long? = null,
    val koreanName: String,
    val iconRef: String? = null,
) {
    init {
        require(koreanName.isNotBlank()) { "ingredient.koreanName 은 blank 일 수 없습니다" }
    }
}
