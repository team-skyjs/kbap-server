package com.kbap.domain.food

import org.springframework.data.jpa.repository.JpaRepository

internal interface FoodAvoidanceSubstanceJpaRepository : JpaRepository<FoodAvoidanceSubstance, Long> {
    fun findByFoodIdIn(foodIds: Collection<Long>): List<FoodAvoidanceSubstance>
}
