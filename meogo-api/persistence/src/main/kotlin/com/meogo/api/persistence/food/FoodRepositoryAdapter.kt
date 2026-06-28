package com.meogo.api.persistence.food

import com.meogo.api.food.Food
import com.meogo.api.food.FoodRepository
import org.springframework.stereotype.Repository

@Repository
class FoodRepositoryAdapter(
    private val jpaRepository: FoodJpaRepository,
) : FoodRepository {
    override fun findByKoreanName(name: String): Food? =
        jpaRepository.findByKoreanNameWithGraph(name.trim())?.toDomain()
}
