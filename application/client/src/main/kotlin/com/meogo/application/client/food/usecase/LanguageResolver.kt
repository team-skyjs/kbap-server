package com.meogo.application.client.food.usecase

import com.meogo.core.food.LanguageCode
import org.springframework.stereotype.Component

@Component
class LanguageResolver {
    fun resolve(lang: String?): LanguageCode = LanguageCode.from(lang)
}
