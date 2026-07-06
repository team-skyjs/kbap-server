package com.meogo.core.research

class FoodContentSelector {
    fun select(foodId: Long, orderedModelScorings: List<ModelScoring>): FoodContent {
        val selected = orderedModelScorings.firstOrNull { hasText(foodId, it) }
            ?: return FoodContent(nameTranslations = emptyMap(), description = null)
        return FoodContent(
            nameTranslations = selected.nameTranslations[foodId] ?: emptyMap(),
            description = selected.descriptions[foodId],
        )
    }

    private fun hasText(foodId: Long, scoring: ModelScoring): Boolean {
        val hasName = scoring.nameTranslations[foodId]?.isNotEmpty() == true
        val hasDescription = scoring.descriptions.containsKey(foodId)
        return hasName || hasDescription
    }
}
