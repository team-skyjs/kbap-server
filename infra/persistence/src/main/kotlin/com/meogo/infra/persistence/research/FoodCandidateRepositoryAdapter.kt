package com.meogo.infra.persistence.research

import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.research.candidate.FoodCandidate
import com.meogo.core.research.candidate.FoodCandidateRepository
import com.meogo.core.research.candidate.SubstanceSnapshot
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class FoodCandidateRepositoryAdapter(
    private val foodCandidateJpaRepository: FoodCandidateJpaRepository,
) : FoodCandidateRepository {
    override fun create(koreanName: String, koreanDescription: String): FoodCandidate {
        foodCandidateJpaRepository.findByKoreanName(koreanName)?.let {
            return it.toDomain()
        }
        val domain = FoodCandidate.create(
            koreanName = koreanName,
            koreanDescription = koreanDescription,
            descriptionTranslations = emptyMap(),
            substanceMapping = emptyList(),
        )
        return foodCandidateJpaRepository.save(FoodCandidateJpaEntity.from(domain)).toDomain()
    }

    override fun findPromotable(afterId: Long, size: Int): List<FoodCandidate> =
        foodCandidateJpaRepository.findPromotable(afterId = afterId, size = size)
            .map { it.toDomain() }

    @Transactional
    override fun updateSubstanceMapping(candidateId: Long, mapping: List<SubstanceSnapshot>) {
        foodCandidateJpaRepository.updateSubstanceMapping(
            candidateId,
            mapping.map { SubstanceMappingJson(it.code, it.inclusionPercent) },
        )
    }

    @Transactional
    override fun updateDescriptionTranslations(candidateId: Long, translations: Map<LanguageCode, String>) {
        foodCandidateJpaRepository.updateDescriptionTranslations(
            candidateId,
            translations.entries.associate { it.key.code to it.value },
        )
    }

    @Transactional
    override fun markPublished(candidateId: Long, foodId: Long) {
        foodCandidateJpaRepository.markPublished(candidateId, foodId)
    }
}
