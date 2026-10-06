package com.kbap.common.port.llm

import com.kbap.common.domain.LanguageCode

fun interface TextTranslator {
    fun translate(text: String, target: LanguageCode): TranslatedText
}

data class TranslatedText(val text: String, val sourceLanguageTag: String?)
