package com.meogo.infra.persistence.food

import com.meogo.core.food.Food
import com.meogo.core.food.FoodRepository
import com.meogo.core.kernel.lang.LanguageCode
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class FoodRepositoryAdapter(
    private val foodJpaRepository: FoodJpaRepository,
) : FoodRepository {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun findById(id: Long): Food? =
        foodJpaRepository.findByIdInWithAvoidanceSubstances(listOf(id))
            .firstOrNull()
            ?.toDomain()
            ?.takeIf { it.isReady() }

    override fun findFoodPage(cursor: Long?, size: Int): List<Food> {
        val ids = foodJpaRepository.findFoodPageIds(cursor, PageRequest.of(0, size))
        return loadDescending(ids)
    }

    override fun searchFoodPage(keyword: String, lang: LanguageCode, cursor: Long?, size: Int): List<Food> {
        val jsonPath = if (lang == LanguageCode.KO) null else "$.\"${lang.code}\""
        val ids = foodJpaRepository.searchFoodPageIds(escapeLikeWildcards(keyword), jsonPath, cursor, size)
        return loadDescending(ids)
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

    override fun findByKoreanMatchKeys(keys: Set<String>): Map<String, Food> {
        if (keys.isEmpty()) return emptyMap()
        val entities = foodJpaRepository.findByKoreanMatchKeyInWithAvoidanceSubstances(keys)
        val grouped = entities.groupBy { it.koreanMatchKey }
        grouped.filterValues { it.size > 1 }.forEach { (key, duplicates) ->
            log.warn("동음이의 음식 매칭 — key={} 에 {} 개 음식({}), 최소 id 로 매칭", key, duplicates.size, duplicates.map { it.id })
        }
        return grouped.mapValues { (_, duplicates) -> duplicates.minBy { it.id }.toDomain() }
    }

    @Transactional
    override fun createIncomplete(koreanNames: Set<String>): Map<String, Food> {
        if (koreanNames.isEmpty()) return emptyMap()

        val existing = foodJpaRepository.findByKoreanNameIn(koreanNames)
        val missing = koreanNames - existing.map { it.koreanName }.toSet()
        if (missing.isEmpty()) return existing.associate { it.koreanName to it.toDomain() }

        val created = try {
            foodJpaRepository.saveAll(missing.map { FoodJpaEntity.from(Food.incomplete(it)) })
        } catch (e: DataIntegrityViolationException) {
            log.warn("미완성 음식 생성 경합 — koreanNames={}, 기존 음식 재조회", missing, e)
            foodJpaRepository.findByKoreanNameIn(missing)
        }

        val resolved = (existing + created).associate { it.koreanName to it.toDomain() }
        val unresolved = koreanNames - resolved.keys
        if (unresolved.isNotEmpty()) {
            log.warn("미완성 음식 생성 실패 — 소프트 삭제된 동명 음식이 있을 수 있습니다: {}", unresolved)
        }
        return resolved
    }
}
