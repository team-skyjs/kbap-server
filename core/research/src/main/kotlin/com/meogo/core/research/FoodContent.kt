package com.meogo.core.research

import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.lang.LocalizedText

data class FoodContent(
    val nameTranslations: Map<LanguageCode, String>,
    val description: LocalizedText?,
)
