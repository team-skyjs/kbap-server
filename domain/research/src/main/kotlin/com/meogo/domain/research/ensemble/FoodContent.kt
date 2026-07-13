package com.meogo.domain.research.ensemble

import com.meogo.core.lang.LanguageCode
import com.meogo.core.lang.LocalizedText

data class FoodContent(
    val nameTranslations: Map<LanguageCode, String>,
    val description: LocalizedText?,
)
