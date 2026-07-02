package com.meogo.infra.persistence.avoidance

import com.meogo.core.avoidance.AvoidanceCategory
import com.meogo.core.avoidance.AvoidanceSubstance
import org.springframework.stereotype.Component

@Component
class AvoidanceSubstanceReconstitutor(
    private val substanceJpaRepository: AvoidanceSubstanceJpaRepository,
    private val categoryJpaRepository: AvoidanceSubstanceCategoryJpaRepository,
) {
    fun byIds(substanceIds: Set<Long>): List<AvoidanceSubstance> {
        if (substanceIds.isEmpty()) return emptyList()
        return fromRows(substanceJpaRepository.findByIdIn(substanceIds))
    }

    fun fromRows(rows: List<AvoidanceSubstanceJpaEntity>): List<AvoidanceSubstance> {
        if (rows.isEmpty()) return emptyList()
        val categoriesBySubstanceId = categoryJpaRepository
            .findBySubstanceIdIn(rows.map { it.id }.toSet())
            .groupBy({ it.substanceId }, { runCatching { AvoidanceCategory.valueOf(it.category) }.getOrNull() })
            .mapValues { (_, categories) -> categories.filterNotNull().toSet() }
        return rows.mapNotNull { row ->
            val categories = categoriesBySubstanceId[row.id]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            runCatching { row.toDomain(categories) }.getOrNull()
        }
    }
}
