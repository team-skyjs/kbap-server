package com.kbap.common.port.llm

import com.kbap.common.domain.food.model.TargetLanguageTexts

fun interface FoodNameTranslationClient {
    fun call(koreanName: String): TargetLanguageTexts
}
