package com.meogo.core.research.parse

import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.lang.LocalizedText

data class ModelScoring(
    val included: Map<Long, List<SubstanceJudgement>> = emptyMap(),
    val nameTranslations: Map<Long, Map<LanguageCode, String>> = emptyMap(),
    val descriptions: Map<Long, LocalizedText> = emptyMap(),
)
