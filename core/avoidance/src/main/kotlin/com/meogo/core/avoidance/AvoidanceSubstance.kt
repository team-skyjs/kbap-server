package com.meogo.core.avoidance

import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.stereotype.AggregateRoot

@AggregateRoot
class AvoidanceSubstance private constructor(
    val id: Long,
    val code: AvoidanceSubstanceCode,
    val koreanName: String,
    val translations: Map<LanguageCode, String>,
    val categories: Set<AvoidanceCategory>,
) {
    init {
        require(categories.isNotEmpty() && categories.size <= 3)
        require(koreanName.isNotBlank())
    }

    fun displayName(lang: LanguageCode): String =
        if (lang == LanguageCode.KO) {
            koreanName
        } else {
            translations[lang] ?: koreanName
        }

    fun belongsTo(category: AvoidanceCategory): Boolean = category in categories

    companion object {
        fun reconstitute(
            id: Long,
            code: AvoidanceSubstanceCode,
            koreanName: String,
            translations: Map<LanguageCode, String>,
            categories: Set<AvoidanceCategory>,
        ): AvoidanceSubstance =
            AvoidanceSubstance(
                id = id,
                code = code,
                koreanName = koreanName,
                translations = translations,
                categories = categories,
            )
    }
}
