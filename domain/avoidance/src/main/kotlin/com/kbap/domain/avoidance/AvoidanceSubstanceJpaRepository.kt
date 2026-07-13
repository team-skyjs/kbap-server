package com.kbap.domain.avoidance

import org.springframework.data.jpa.repository.JpaRepository

internal interface AvoidanceSubstanceJpaRepository : JpaRepository<AvoidanceSubstanceJpaEntity, Long> {
    fun findByCodeIn(codes: Set<String>): List<AvoidanceSubstanceJpaEntity>

    fun findByIdIn(ids: Set<Long>): List<AvoidanceSubstanceJpaEntity>
}
