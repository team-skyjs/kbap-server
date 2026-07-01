package com.meogo.infra.persistence.avoidance

import com.meogo.core.avoidance.AvoidanceCategory
import org.springframework.data.jpa.repository.JpaRepository

interface AvoidanceSubstanceCategoryJpaRepository : JpaRepository<AvoidanceSubstanceCategoryJpaEntity, Long> {
    fun findByCategory(category: AvoidanceCategory): List<AvoidanceSubstanceCategoryJpaEntity>
}
