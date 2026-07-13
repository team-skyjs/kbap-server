package com.meogo.domain.research.parse

import com.meogo.core.lang.LanguageCode
import com.meogo.core.lang.LocalizedText

data class ModelScoring(
    val included: Map<Long, List<SubstanceJudgement>> = emptyMap(),
    val nameTranslations: Map<Long, Map<LanguageCode, String>> = emptyMap(),
    val descriptions: Map<Long, LocalizedText> = emptyMap(),
    val coveredFoodIds: Set<Long> = emptySet(),
)
