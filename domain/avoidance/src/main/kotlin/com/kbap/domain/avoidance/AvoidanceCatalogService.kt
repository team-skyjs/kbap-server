package com.kbap.domain.avoidance

import org.springframework.stereotype.Service

@Service
class AvoidanceCatalogService internal constructor(
    private val avoidanceSubstanceRepository: AvoidanceSubstanceJpaRepository,
) {
    fun findByCodes(codes: Set<AvoidanceSubstanceCode>): List<AvoidanceSubstance> =
        if (codes.isEmpty()) emptyList() else avoidanceSubstanceRepository.findByCodeIn(codes)
}
