package com.meogo.core.research.candidate

import com.meogo.core.kernel.lang.LanguageCode

interface FoodCandidateRepository {
    fun create(koreanName: String, koreanDescription: String): FoodCandidate

    fun findPromotable(afterId: Long, size: Int): List<FoodCandidate>

    fun updateSubstanceMapping(candidateId: Long, mapping: List<SubstanceSnapshot>)

    fun updateDescriptionTranslations(candidateId: Long, translations: Map<LanguageCode, String>)

    fun markPublished(candidateId: Long, foodId: Long)
}
