package com.meogo.core.food

import com.meogo.core.kernel.lang.LanguageCode

data class FoodContent(
    val koreanName: String,
    val description: String,
    val nameTranslations: Map<LanguageCode, String> = emptyMap(),
    val descriptionTranslations: Map<LanguageCode, String> = emptyMap(),
) {
    init {
        require(koreanName.isNotBlank()) { "food.koreanName 은 blank 일 수 없습니다" }
        require(koreanName.length <= MAX_NAME_LENGTH) { "food.koreanName 은 ${MAX_NAME_LENGTH}자를 초과할 수 없습니다" }
        require(description.isNotBlank()) { "food.description 은 blank 일 수 없습니다" }
        require(description.length <= MAX_DESCRIPTION_LENGTH) { "food.description 은 ${MAX_DESCRIPTION_LENGTH}자를 초과할 수 없습니다" }
    }

    fun name(lang: LanguageCode): String = if (lang == LanguageCode.KO) koreanName else nameTranslations[lang] ?: koreanName

    fun description(lang: LanguageCode): String = if (lang == LanguageCode.KO) description else descriptionTranslations[lang] ?: description

    companion object {
        const val MAX_NAME_LENGTH = 255
        const val MAX_DESCRIPTION_LENGTH = 255
    }
}
