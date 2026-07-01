package com.meogo.infra.persistence.avoidance

import com.meogo.core.avoidance.AvoidanceCategory
import com.meogo.core.avoidance.AvoidanceSubstance
import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.avoidance.AvoidanceSubstanceRepository
import org.springframework.stereotype.Repository

@Repository
class AvoidanceSubstanceRepositoryAdapter(
    private val avoidanceSubstanceJpaRepository: AvoidanceSubstanceJpaRepository,
    private val avoidanceSubstanceCategoryJpaRepository: AvoidanceSubstanceCategoryJpaRepository,
    private val avoidanceSubstanceReconstitutor: AvoidanceSubstanceReconstitutor,
) : AvoidanceSubstanceRepository {
    override fun byCategory(category: AvoidanceCategory): List<AvoidanceSubstance> {
        val substanceIds = avoidanceSubstanceCategoryJpaRepository.findByCategory(category.name)
            .map { it.substanceId }
            .toSet()
        return avoidanceSubstanceReconstitutor.byIds(substanceIds)
    }

    override fun findByCodes(codes: Set<AvoidanceSubstanceCode>): List<AvoidanceSubstance> {
        if (codes.isEmpty()) return emptyList()
        val rows = avoidanceSubstanceJpaRepository.findByCodeIn(codes.map { it.name }.toSet())
        return avoidanceSubstanceReconstitutor.fromRows(rows)
    }
}
