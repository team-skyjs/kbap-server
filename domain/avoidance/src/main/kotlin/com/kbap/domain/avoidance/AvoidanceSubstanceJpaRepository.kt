package com.kbap.domain.avoidance

import org.springframework.data.jpa.repository.JpaRepository

internal interface AvoidanceSubstanceJpaRepository : JpaRepository<AvoidanceSubstance, Long> {
    fun findByCodeIn(codes: Set<AvoidanceSubstanceCode>): List<AvoidanceSubstance>
}
