package com.meogo.core.avoidance

import com.meogo.core.kernel.lang.LanguageCode

interface AvoidanceSubstanceRepository {
    fun byCategory(category: AvoidanceCategory): List<AvoidanceSubstance>

    fun translatedName(substance: AvoidanceSubstance, lang: LanguageCode): String

    fun findByCodes(codes: Set<String>): List<AvoidanceSubstance>
}
