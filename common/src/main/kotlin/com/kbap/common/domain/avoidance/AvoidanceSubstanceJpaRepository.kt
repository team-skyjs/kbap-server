package com.kbap.common.domain.avoidance

import com.kbap.common.domain.avoidance.model.AvoidanceSubstance
import com.kbap.common.domain.avoidance.model.AvoidanceSubstanceCode
import org.springframework.data.jpa.repository.JpaRepository

interface AvoidanceSubstanceJpaRepository : JpaRepository<AvoidanceSubstance, Long> {
    fun findByCodeIn(codes: Set<AvoidanceSubstanceCode>): List<AvoidanceSubstance>
}
