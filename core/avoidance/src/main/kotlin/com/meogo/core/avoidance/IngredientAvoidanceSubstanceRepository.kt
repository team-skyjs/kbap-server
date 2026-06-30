package com.meogo.core.avoidance

interface IngredientAvoidanceSubstanceRepository {
    fun findByIngredientIds(ingredientIds: Set<Long>): Map<Long, Set<AvoidanceSubstance>>
}
