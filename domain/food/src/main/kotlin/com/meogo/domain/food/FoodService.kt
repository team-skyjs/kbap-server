package com.meogo.domain.food

import com.meogo.core.lang.LanguageCode
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FoodService internal constructor(
    private val foodJpaRepository: FoodJpaRepository,
    private val entityManager: EntityManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun findById(id: Long): Food? =
        foodJpaRepository.findByIdInWithAvoidanceSubstances(listOf(id))
            .firstOrNull()
            ?.toDomain()
            ?.takeIf { it.isReady() }

    fun findRandomReady(size: Int): List<Food> {
        val ids = foodJpaRepository.findRandomReadyIds(size)
        if (ids.isEmpty()) return emptyList()
        return foodJpaRepository.findByIdInWithAvoidanceSubstances(ids).map { it.toDomain() }
    }

    fun findAllReadyByIds(ids: List<Long>): List<Food> {
        if (ids.isEmpty()) return emptyList()
        return foodJpaRepository.findByIdInWithAvoidanceSubstances(ids)
            .map { it.toDomain() }
            .filter { it.isReady() }
    }

    fun findFoodPage(cursor: Long?, size: Int): List<Food> {
        val ids = foodJpaRepository.findFoodPageIds(cursor, PageRequest.of(0, size))
        return loadDescending(ids)
    }

    fun searchFoodPage(keyword: String, lang: LanguageCode, cursor: Long?, size: Int): List<Food> {
        val jsonPath = if (lang == LanguageCode.KO) null else "$.\"${lang.code}\""
        val ids = foodJpaRepository.searchFoodPageIds(escapeLikeWildcards(keyword), jsonPath, cursor, size)
        return loadDescending(ids)
    }

    fun findByKoreanMatchKeys(keys: Set<String>): Map<String, Food> {
        if (keys.isEmpty()) return emptyMap()
        val entities = foodJpaRepository.findByKoreanMatchKeyInWithAvoidanceSubstances(keys)
        val grouped = entities.groupBy { it.koreanMatchKey }
        grouped.filterValues { it.size > 1 }.forEach { (key, duplicates) ->
            log.warn("동음이의 음식 매칭 — key={} 에 {} 개 음식({}), 최소 id 로 매칭", key, duplicates.size, duplicates.map { it.id })
        }
        return grouped.mapValues { (_, duplicates) -> duplicates.minBy { it.id }.toDomain() }
    }

    @Transactional
    fun createIncomplete(koreanNames: Set<String>): Map<String, Food> {
        if (koreanNames.isEmpty()) return emptyMap()

        upsertIncomplete(koreanNames.map { Food.incomplete(it) })


        val resolved = foodJpaRepository.findByKoreanNameIn(koreanNames)
            .associate { it.koreanName to it.toDomain() }
        val unresolved = koreanNames - resolved.keys
        if (unresolved.isNotEmpty()) {
            log.warn("미완성 음식 등록 누락 — 소프트 삭제된 동명 음식이 있습니다: {}", unresolved)
        }
        return resolved
    }

    fun nextChunk(page: Int, size: Int): List<Food> {
        val ids = foodJpaRepository.findFoodIds(PageRequest.of(page, size))
        if (ids.isEmpty()) return emptyList()
        return foodJpaRepository.findByIdInWithAvoidanceSubstances(ids).map { it.toDomain() }
    }

    private fun upsertIncomplete(foods: List<Food>) {
        val rows = foods.joinToString(", ") { "(?, ?, ?, '{}', '{}', ?, 'ACTIVE', NOW(6), NOW(6))" }
        val query = entityManager.createNativeQuery(
            """
            insert into food (korean_name, description, spiciness, name_translations, description_translations,
                              content_status, status, created_at, updated_at)
            values $rows
            on duplicate key update id = id
            """.trimIndent(),
        )
        foods.forEachIndexed { index, food ->
            query.setParameter(index * 4 + 1, food.koreanName())
            query.setParameter(index * 4 + 2, food.content.description.korean)
            query.setParameter(index * 4 + 3, food.spiciness.value)
            query.setParameter(index * 4 + 4, food.contentStatus.name)
        }
        query.executeUpdate()
    }

    private fun escapeLikeWildcards(keyword: String): String =
        keyword
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    private fun loadDescending(ids: List<Long>): List<Food> {
        if (ids.isEmpty()) return emptyList()
        return foodJpaRepository.findByIdInWithAvoidanceSubstancesDesc(ids).map { it.toDomain() }
    }
}
