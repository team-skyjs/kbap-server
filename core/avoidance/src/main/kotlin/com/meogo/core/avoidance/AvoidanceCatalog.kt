package com.meogo.core.avoidance

import com.meogo.core.kernel.lang.LanguageCode

object AvoidanceCatalog {
    fun displayName(substance: AvoidanceSubstance, lang: LanguageCode): String =
        if (lang == LanguageCode.KO) {
            substance.koName
        } else {
            AvoidanceSubstanceTranslations.translations[substance]?.get(lang) ?: substance.koName
        }

    fun byCategory(category: AvoidanceCategory): List<AvoidanceSubstance> =
        AvoidanceSubstance.entries.filter { category in it.categories }

    fun all(): List<AvoidanceSubstance> = AvoidanceSubstance.entries.toList()
}
