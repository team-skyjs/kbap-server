package com.meogo.api.application.food

import com.meogo.api.food.LanguageCode
import org.springframework.stereotype.Component

@Component
class LanguageResolver {
    fun resolve(lang: String?): LanguageCode = LanguageCode.from(lang)
}
