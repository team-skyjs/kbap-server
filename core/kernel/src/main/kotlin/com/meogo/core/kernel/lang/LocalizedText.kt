package com.meogo.core.kernel.lang

data class LocalizedText(
    val korean: String,
    val translations: Map<LanguageCode, String> = emptyMap(),
) {
    init {
        require(korean.isNotBlank()) { "LocalizedText.korean 은 blank 일 수 없습니다" }
    }

    fun resolve(lang: LanguageCode): String =
        if (lang == LanguageCode.KO) korean else translations[lang] ?: korean
}
