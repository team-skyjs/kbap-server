package com.meogo.domain.avoidance

import org.springframework.stereotype.Component

@Component
internal class AvoidanceSubstanceReconstitutor(
    private val substanceJpaRepository: AvoidanceSubstanceJpaRepository,
) {
    fun byIds(substanceIds: Set<Long>): List<AvoidanceSubstance> {
        if (substanceIds.isEmpty()) return emptyList()
        return fromRows(substanceJpaRepository.findByIdIn(substanceIds))
    }

    fun fromRows(rows: List<AvoidanceSubstanceJpaEntity>): List<AvoidanceSubstance> =
        rows.mapNotNull { row -> runCatching { row.toDomain() }.getOrNull() }
}
