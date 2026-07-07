package com.meogo.infra.persistence.food

import com.meogo.core.food.Food
import com.meogo.core.food.FoodRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class FoodRepositoryAdapter(
    private val foodJpaRepository: FoodJpaRepository,
) : FoodRepository {
    override fun findByKoreanName(name: String): Food? =
        foodJpaRepository.findByKoreanNameWithAvoidanceSubstances(name.trim())?.toDomain()

    @Transactional
    override fun save(food: Food): Food =
        foodJpaRepository.save(FoodJpaEntity.from(food)).toDomain()
}
