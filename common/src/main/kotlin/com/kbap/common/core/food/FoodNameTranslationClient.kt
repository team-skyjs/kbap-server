package com.kbap.common.core.food

fun interface FoodNameTranslationClient {
    fun call(koreanName: String): TargetLanguageTexts
}
