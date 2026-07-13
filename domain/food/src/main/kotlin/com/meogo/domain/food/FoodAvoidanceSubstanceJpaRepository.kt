package com.meogo.domain.food

import org.springframework.data.jpa.repository.JpaRepository

internal interface FoodAvoidanceSubstanceJpaRepository : JpaRepository<FoodAvoidanceSubstanceJpaEntity, Long> {
    fun findByFoodIdIn(foodIds: Collection<Long>): List<FoodAvoidanceSubstanceJpaEntity>
}
