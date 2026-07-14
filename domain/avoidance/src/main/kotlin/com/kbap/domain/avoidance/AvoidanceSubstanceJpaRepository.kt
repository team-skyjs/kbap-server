package com.kbap.domain.avoidance

import com.kbap.domain.avoidance.model.AvoidanceSubstance
import com.kbap.domain.avoidance.model.AvoidanceSubstanceCode
import org.springframework.data.jpa.repository.JpaRepository

internal interface AvoidanceSubstanceJpaRepository : JpaRepository<AvoidanceSubstance, Long> {
    fun findByCodeIn(codes: Set<AvoidanceSubstanceCode>): List<AvoidanceSubstance>
}
