package com.meogo.application.client.food.usecase

import com.meogo.core.kernel.lang.LanguageCode
import org.springframework.stereotype.Component

@Component
class LanguageResolver {
    fun resolve(lang: String?): LanguageCode = LanguageCode.from(lang)
}
