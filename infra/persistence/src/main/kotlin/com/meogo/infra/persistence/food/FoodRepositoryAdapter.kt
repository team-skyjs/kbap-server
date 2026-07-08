package com.meogo.infra.persistence.food

import com.meogo.core.food.Food
import com.meogo.core.food.FoodRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository

@Repository
class FoodRepositoryAdapter(
    private val foodJpaRepository: FoodJpaRepository,
) : FoodRepository {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun findById(id: Long): Food? =
        foodJpaRepository.findByIdInWithAvoidanceSubstances(listOf(id)).firstOrNull()?.toDomain()

    override fun findMenuPage(cursor: Long?, size: Int): List<Food> {
        val ids = foodJpaRepository.findMenuPageIds(cursor, PageRequest.of(0, size))
        if (ids.isEmpty()) return emptyList()
        return foodJpaRepository.findByIdInWithAvoidanceSubstancesDesc(ids).map { it.toDomain() }
    }

    override fun findFoodIdByKoreanMatchKey(key: String): Long? {
        val ids = foodJpaRepository.findIdsByKoreanMatchKey(key)
        if (ids.size > 1) {
            log.warn("동음이의 음식 매칭 — key={} 에 {} 개 음식({}), 최소 id 로 매칭", key, ids.size, ids)
        }
        return ids.firstOrNull()
    }
}
