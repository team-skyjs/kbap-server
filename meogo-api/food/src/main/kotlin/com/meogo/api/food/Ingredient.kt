package com.meogo.api.food

data class Ingredient(
    val id: Long? = null,
    val koreanName: String,
    val names: Map<LanguageCode, String>,
    val iconRef: String? = null,
) {
    init {
        require(koreanName.isNotBlank()) { "ingredient.koreanName 은 blank 일 수 없습니다" }
    }

    fun nameFor(lang: LanguageCode): String =
        if (lang == LanguageCode.KO) koreanName else names[lang] ?: koreanName
}
