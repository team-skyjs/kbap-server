package com.meogo.core.research

import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.lang.LocalizedText

data class FoodScoringResult(
    val foodId: Long,
    val status: FoodScoringStatus,
    val scores: List<FoodInclusionScore>,
    val nameTranslations: Map<LanguageCode, String>,
    val description: LocalizedText?,
)
