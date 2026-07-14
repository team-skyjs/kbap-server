package com.kbap.domain.research.ensemble

import com.kbap.core.lang.LanguageCode
import com.kbap.core.lang.LocalizedText

data class FoodScoringResult(
    val foodId: Long,
    val status: FoodScoringStatus,
    val scores: List<FoodInclusionScore>,
    val nameTranslations: Map<LanguageCode, String>,
    val description: LocalizedText?,
)
