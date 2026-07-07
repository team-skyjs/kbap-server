package com.meogo.infra.persistence.food

import com.meogo.core.food.Food
import com.meogo.core.food.FoodRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository

@Repository
class FoodRepositoryAdapter(
    private val foodJpaRepository: FoodJpaRepository,
) : FoodRepository {
    override fun findByKoreanName(name: String): Food? =
        foodJpaRepository.findByKoreanNameWithAvoidanceSubstances(name.trim())?.toDomain()

    override fun findMenuPage(cursor: Long?, size: Int): List<Food> {
        val ids = foodJpaRepository.findMenuPageIds(cursor, PageRequest.of(0, size))
        if (ids.isEmpty()) return emptyList()
        return foodJpaRepository.findByIdInWithAvoidanceSubstancesDesc(ids).map { it.toDomain() }
    }
}
