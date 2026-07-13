package com.kbap.domain.avoidance

import org.springframework.stereotype.Service

@Service
class AvoidanceSubstanceService internal constructor(
    private val avoidanceSubstanceJpaRepository: AvoidanceSubstanceJpaRepository,
    private val avoidanceSubstanceReconstitutor: AvoidanceSubstanceReconstitutor,
) {
    fun findByCodes(codes: Set<AvoidanceSubstanceCode>): List<AvoidanceSubstance> {
        if (codes.isEmpty()) return emptyList()
        val rows = avoidanceSubstanceJpaRepository.findByCodeIn(codes.map { it.name }.toSet())
        return avoidanceSubstanceReconstitutor.fromRows(rows)
    }
}
