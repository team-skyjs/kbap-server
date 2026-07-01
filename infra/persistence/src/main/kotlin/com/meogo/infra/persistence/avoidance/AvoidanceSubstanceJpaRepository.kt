package com.meogo.infra.persistence.avoidance

import org.springframework.data.jpa.repository.JpaRepository

interface AvoidanceSubstanceJpaRepository : JpaRepository<AvoidanceSubstanceJpaEntity, Long> {
    fun findByCodeIn(codes: Set<String>): List<AvoidanceSubstanceJpaEntity>

    fun findByIdIn(ids: Set<Long>): List<AvoidanceSubstanceJpaEntity>
}
