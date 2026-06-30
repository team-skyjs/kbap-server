package com.meogo.application.client.food.usecase

import com.meogo.core.avoidance.AvoidanceSubstance
import com.meogo.core.avoidance.IngredientAvoidanceSubstanceRepository
import org.springframework.stereotype.Component

@Component
class FoodAvoidanceSubstanceResolver(
    private val repository: IngredientAvoidanceSubstanceRepository,
) {
    fun resolve(ingredientIds: Set<Long>): Set<AvoidanceSubstance> =
        repository.findByIngredientIds(ingredientIds).values.flatten().toSet()
}
