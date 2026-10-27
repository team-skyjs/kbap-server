package com.kbap.common.domain.food.model

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.LocalizedText

object FoodName {
    fun resolve(koreanName: String, displayName: String, translations: Map<String, String>, lang: LanguageCode): String =
        LocalizedText(
            korean = displayName.ifBlank { koreanName },
            translations = translations.mapNotNull { (key, value) ->
                LanguageCode.entries.firstOrNull { it.code == key }?.let { it to value }
            }.toMap(),
        ).resolve(lang)
}
