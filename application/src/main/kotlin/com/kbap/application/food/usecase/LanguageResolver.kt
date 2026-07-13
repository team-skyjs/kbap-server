package com.kbap.application.food.usecase

import com.kbap.core.lang.LanguageCode
import org.springframework.stereotype.Component

@Component
class LanguageResolver {
    fun resolve(lang: String?): LanguageCode = LanguageCode.from(lang)
}
