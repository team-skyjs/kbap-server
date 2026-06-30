package com.meogo.infra.persistence.avoidance

import com.meogo.core.avoidance.AvoidanceSubstance
import com.meogo.core.avoidance.IngredientAvoidanceSubstanceRepository
import org.springframework.stereotype.Repository

@Repository
class IngredientAvoidanceSubstanceRepositoryAdapter(
    private val ingredientAvoidanceSubstanceJpaRepository: IngredientAvoidanceSubstanceJpaRepository,
    private val avoidanceSubstanceJpaRepository: AvoidanceSubstanceJpaRepository,
) : IngredientAvoidanceSubstanceRepository {
    override fun findByIngredientIds(ingredientIds: Set<Long>): Map<Long, Set<AvoidanceSubstance>> {
        if (ingredientIds.isEmpty()) return emptyMap()

        val mappings = ingredientAvoidanceSubstanceJpaRepository.findByIngredientIdIn(ingredientIds)
        if (mappings.isEmpty()) return emptyMap()

        val substanceById = avoidanceSubstanceJpaRepository
            .findByIdIn(mappings.map { it.substanceId }.toSet())
            .associateBy { it.id }

        return mappings
            .groupBy { it.ingredientId }
            .mapValues { (_, ingredientMappings) ->
                ingredientMappings
                    .mapNotNull { substanceById[it.substanceId]?.code }
                    .mapNotNull { code -> runCatching { AvoidanceSubstance.valueOf(code) }.getOrNull() }
                    .toSet()
            }
            .filterValues { it.isNotEmpty() }
    }
}
