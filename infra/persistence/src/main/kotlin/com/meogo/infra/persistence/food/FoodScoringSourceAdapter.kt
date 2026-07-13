package com.meogo.infra.persistence.food

import com.meogo.domain.food.Food
import com.meogo.domain.food.FoodScoringSource
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository

@Repository
class FoodScoringSourceAdapter(
    private val foodJpaRepository: FoodJpaRepository,
) : FoodScoringSource {
    override fun nextChunk(page: Int, size: Int): List<Food> {
        val ids = foodJpaRepository.findFoodIds(PageRequest.of(page, size))
        if (ids.isEmpty()) return emptyList()
        return foodJpaRepository.findByIdInWithAvoidanceSubstances(ids).map { it.toDomain() }
    }
}
