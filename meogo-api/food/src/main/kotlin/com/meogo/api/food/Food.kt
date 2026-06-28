package com.meogo.api.food

data class Food(
    val id: Long? = null,
    val koreanName: String,
    val names: Map<LanguageCode, String>,
    val imageRef: String? = null,
    val ingredients: List<FoodIngredient>,
) {
    init {
        require(koreanName.isNotBlank()) { "food.koreanName 은 blank 일 수 없습니다" }
    }

    fun nameFor(lang: LanguageCode): String =
        if (lang == LanguageCode.KO) koreanName else names[lang] ?: koreanName
}
