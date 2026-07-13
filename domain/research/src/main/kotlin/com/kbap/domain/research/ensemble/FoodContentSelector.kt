package com.kbap.domain.research.ensemble

import com.kbap.core.lang.LanguageCode
import com.kbap.core.lang.LocalizedText
import com.kbap.domain.research.parse.ModelScoring

class FoodContentSelector {
    fun select(foodId: Long, orderedModelScoring: List<ModelScoring>): FoodContent {
        val nameTranslations = mergeByLanguage(orderedModelScoring.map { it.nameTranslations[foodId].orEmpty() })
        val description = mergeDescriptions(orderedModelScoring.mapNotNull { it.descriptions[foodId] })
        return FoodContent(nameTranslations = nameTranslations, description = description)
    }

    private fun mergeByLanguage(orderedTranslations: List<Map<LanguageCode, String>>): Map<LanguageCode, String> {
        val merged = mutableMapOf<LanguageCode, String>()
        orderedTranslations.forEach { translations ->
            translations.forEach { (language, value) -> merged.putIfAbsent(language, value) }
        }
        return merged
    }

    private fun mergeDescriptions(orderedDescriptions: List<LocalizedText>): LocalizedText? {
        if (orderedDescriptions.isEmpty()) {
            return null
        }
        return LocalizedText(
            korean = orderedDescriptions.first().korean,
            translations = mergeByLanguage(orderedDescriptions.map { it.translations }),
        )
    }
}
