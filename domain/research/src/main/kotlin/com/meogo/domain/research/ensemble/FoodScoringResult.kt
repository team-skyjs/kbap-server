package com.meogo.domain.research.ensemble

import com.meogo.core.lang.LanguageCode
import com.meogo.core.lang.LocalizedText

data class FoodScoringResult(
    val foodId: Long,
    val status: FoodScoringStatus,
    val scores: List<FoodInclusionScore>,
    val nameTranslations: Map<LanguageCode, String>,
    val description: LocalizedText?,
)
