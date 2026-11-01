package com.kbap.common.port.llm

import com.kbap.common.domain.LanguageCode

fun interface ProcessedFoodTextTranslator {
    fun translate(text: String, target: LanguageCode): TranslatedText

    fun translateName(name: String, target: LanguageCode): TranslatedText = translate(name, target)
}
