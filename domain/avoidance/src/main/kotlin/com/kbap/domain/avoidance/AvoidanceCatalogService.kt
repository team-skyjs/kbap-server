package com.kbap.domain.avoidance

import com.kbap.domain.avoidance.model.AvoidanceSubstance
import com.kbap.domain.avoidance.model.AvoidanceSubstanceCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AvoidanceCatalogService internal constructor(
    private val avoidanceSubstanceRepository: AvoidanceSubstanceJpaRepository,
) {
    @Transactional(readOnly = true)
    fun findByCodes(codes: Set<AvoidanceSubstanceCode>): List<AvoidanceSubstance> =
        if (codes.isEmpty()) emptyList() else avoidanceSubstanceRepository.findByCodeIn(codes)
}
