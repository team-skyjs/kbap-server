package com.kbap.core.food

fun interface FoodNameTranslationClient {
    fun call(koreanName: String): TargetLanguageTexts
}
