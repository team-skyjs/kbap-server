package com.kbap.domain.food

import org.springframework.data.jpa.repository.JpaRepository

interface FoodAvoidanceSubstanceJpaRepository : JpaRepository<FoodAvoidanceSubstance, Long> {
    fun findByFoodIdIn(foodIds: Collection<Long>): List<FoodAvoidanceSubstance>
}
