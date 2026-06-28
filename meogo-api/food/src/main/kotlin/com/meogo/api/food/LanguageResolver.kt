package com.meogo.api.food

import com.meogo.api.core.stereotype.DomainService

@DomainService
class LanguageResolver {
    fun resolve(lang: String?): LanguageCode = LanguageCode.from(lang)
}
