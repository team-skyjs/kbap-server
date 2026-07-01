package com.meogo.infra.persistence.avoidance

import com.meogo.core.avoidance.AvoidanceCategory
import com.meogo.core.avoidance.AvoidanceSubstance
import com.meogo.core.avoidance.AvoidanceSubstanceRepository
import com.meogo.core.kernel.lang.LanguageCode
import org.springframework.stereotype.Repository

@Repository
class AvoidanceSubstanceRepositoryAdapter(
    private val avoidanceSubstanceJpaRepository: AvoidanceSubstanceJpaRepository,
    private val avoidanceSubstanceCategoryJpaRepository: AvoidanceSubstanceCategoryJpaRepository,
) : AvoidanceSubstanceRepository {
    override fun byCategory(category: AvoidanceCategory): List<AvoidanceSubstance> {
        val substanceIds = avoidanceSubstanceCategoryJpaRepository.findByCategory(category)
            .map { it.substanceId }
            .toSet()
        if (substanceIds.isEmpty()) return emptyList()
        return avoidanceSubstanceJpaRepository.findByIdIn(substanceIds)
            .mapNotNull { it.toSubstanceOrNull() }
            .distinct()
    }

    override fun translatedName(substance: AvoidanceSubstance, lang: LanguageCode): String {
        if (lang == LanguageCode.KO) return substance.koName
        val row = avoidanceSubstanceJpaRepository.findByCode(substance.name) ?: return substance.koName
        val translated = when (lang) {
            LanguageCode.KO -> row.koreanName
            LanguageCode.ZH_HANS -> row.nameZhHans
            LanguageCode.EN -> row.nameEn
            LanguageCode.JA -> row.nameJa
            LanguageCode.ZH_HANT -> row.nameZhHant
            LanguageCode.VI -> row.nameVi
            LanguageCode.ID -> row.nameId
            LanguageCode.TH -> row.nameTh
            LanguageCode.RU -> row.nameRu
            LanguageCode.ES -> row.nameEs
        }
        return translated?.takeIf { it.isNotBlank() } ?: row.koreanName
    }

    override fun findByCodes(codes: Set<String>): List<AvoidanceSubstance> =
        avoidanceSubstanceJpaRepository.findByCodeIn(codes).mapNotNull { it.toSubstanceOrNull() }

    private fun AvoidanceSubstanceJpaEntity.toSubstanceOrNull(): AvoidanceSubstance? =
        runCatching { AvoidanceSubstance.valueOf(code) }.getOrNull()
}
