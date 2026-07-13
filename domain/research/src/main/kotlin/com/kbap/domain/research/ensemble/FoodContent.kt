package com.kbap.domain.research.ensemble

import com.kbap.core.lang.LanguageCode
import com.kbap.core.lang.LocalizedText

data class FoodContent(
    val nameTranslations: Map<LanguageCode, String>,
    val description: LocalizedText?,
)
