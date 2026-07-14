package com.kbap.domain.food

import com.kbap.domain.food.model.FoodAvoidanceSubstance
import org.springframework.data.jpa.repository.JpaRepository

internal interface FoodAvoidanceSubstanceJpaRepository : JpaRepository<FoodAvoidanceSubstance, Long> {
    fun findByFoodIdIn(foodIds: Collection<Long>): List<FoodAvoidanceSubstance>
}
