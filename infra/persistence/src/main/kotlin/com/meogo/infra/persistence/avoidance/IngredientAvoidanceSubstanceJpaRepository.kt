package com.meogo.infra.persistence.avoidance

import org.springframework.data.jpa.repository.JpaRepository

interface IngredientAvoidanceSubstanceJpaRepository : JpaRepository<IngredientAvoidanceSubstanceJpaEntity, Long> {
    fun findByIngredientIdIn(ingredientIds: Set<Long>): List<IngredientAvoidanceSubstanceJpaEntity>
}
