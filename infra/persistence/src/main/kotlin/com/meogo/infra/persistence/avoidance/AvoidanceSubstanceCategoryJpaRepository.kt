package com.meogo.infra.persistence.avoidance

import org.springframework.data.jpa.repository.JpaRepository

interface AvoidanceSubstanceCategoryJpaRepository : JpaRepository<AvoidanceSubstanceCategoryJpaEntity, Long> {
    fun findByCategory(category: String): List<AvoidanceSubstanceCategoryJpaEntity>

    fun findBySubstanceIdIn(substanceIds: Set<Long>): List<AvoidanceSubstanceCategoryJpaEntity>
}
