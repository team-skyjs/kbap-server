package com.kbap.application.food

import com.kbap.application.support.LanguageResolver
import com.kbap.application.support.resolveKeyword
import com.kbap.application.support.AvoidedSubstanceHelper
import com.kbap.application.food.dto.BrowseFoodsInput
import com.kbap.application.food.dto.FoodPage
import com.kbap.application.food.dto.FoodSummaryView
import com.kbap.application.food.dto.GetFoodDetailInput
import com.kbap.application.food.dto.GetFoodDetailResult
import com.kbap.application.food.dto.SearchFoodsInput
import com.kbap.core.error.ErrorCode
import com.kbap.core.error.KbapException
import com.kbap.core.lang.LanguageCode
import com.kbap.domain.avoidance.AvoidanceSubstanceCode
import com.kbap.domain.avoidance.AvoidanceSubstanceJpaRepository
import com.kbap.domain.food.Food
import com.kbap.domain.food.FoodJpaRepository
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FoodService(
    private val foodRepository: FoodJpaRepository,
    private val avoidanceSubstanceRepository: AvoidanceSubstanceJpaRepository,
    private val languageResolver: LanguageResolver,
    private val avoidedSubstanceHelper: AvoidedSubstanceHelper,
    private val entityManager: EntityManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun browse(input: BrowseFoodsInput): FoodPage {
        val lang = languageResolver.resolve(input.lang)
        return foodPage(findFoodPage(input.cursor, PAGE_SIZE + 1), lang, input.memberId)
    }

    @Transactional(readOnly = true)
    fun search(input: SearchFoodsInput): FoodPage {
        val keyword = resolveKeyword(input.keyword)
        val lang = languageResolver.resolve(input.lang)
        return foodPage(searchFoodPage(keyword, lang, input.cursor, PAGE_SIZE + 1), lang, input.memberId)
    }

    fun findFoodPage(cursor: Long?, size: Int): List<Food> =
        loadDescending(foodRepository.findFoodPageIds(cursor, PageRequest.of(0, size)))

    fun searchFoodPage(keyword: String, lang: LanguageCode, cursor: Long?, size: Int): List<Food> {
        val jsonPath = if (lang == LanguageCode.KO) null else "$.\"${lang.code}\""
        return loadDescending(foodRepository.searchFoodPageIds(escapeLikeWildcards(keyword), jsonPath, cursor, size))
    }

    @Transactional(readOnly = true)
    fun getDetail(input: GetFoodDetailInput): GetFoodDetailResult {
        val lang = languageResolver.resolve(input.lang)
        val food = findReadyById(input.foodId)
            ?: throw KbapException(ErrorCode.FOOD_NOT_FOUND)

        val orderedSubstances = food.avoidanceSubstancesByProbability()
        val codes = orderedSubstances.map { AvoidanceSubstanceCode.valueOf(it.substanceCode) }.toSet()
        val catalog = findSubstanceCatalog(codes).associateBy { it.code }

        val foodName = food.displayName(lang)
        val description = food.description(lang)

        val avoidanceSubstances = orderedSubstances.map { substance ->
            GetFoodDetailResult.AvoidanceSubstanceView(
                name = catalog.getValue(AvoidanceSubstanceCode.valueOf(substance.substanceCode)).displayName(lang),
                iconRef = null,
                inclusionProbability = substance.inclusionPercent,
                riskStatus = substance.riskLevel(),
            )
        }

        val userAvoidedCodes = avoidedCodeNames(input.memberId)

        return GetFoodDetailResult(
            name = foodName,
            koreanName = food.koreanName().takeIf { it != foodName },
            imageRef = food.imageRef,
            description = description,
            spiciness = food.spiciness,
            overallRiskStatus = food.overallRisk(userAvoidedCodes),
            avoidanceSubstances = avoidanceSubstances,
        )
    }

    fun findReadyById(id: Long): Food? =
        foodRepository.findByIdIn(listOf(id))
            .firstOrNull()
            ?.takeIf { it.isReady() }

    fun findRandomReady(size: Int): List<Food> {
        val ids = foodRepository.findRandomReadyIds(size)
        if (ids.isEmpty()) return emptyList()
        return foodRepository.findByIdIn(ids)
    }

    fun findAllReadyByIds(ids: List<Long>): List<Food> {
        if (ids.isEmpty()) return emptyList()
        return foodRepository.findByIdIn(ids)
            .sortedBy { it.id }
            .filter { it.isReady() }
    }

    fun findByKoreanMatchKeys(keys: Set<String>): Map<String, Food> {
        if (keys.isEmpty()) return emptyMap()
        val entities = foodRepository.findByKoreanMatchKeyIn(keys)
        val grouped = entities.groupBy { it.koreanMatchKey }
        grouped.filterValues { it.size > 1 }.forEach { (key, duplicates) ->
            log.warn("동음이의 음식 매칭 — key={} 에 {} 개 음식({}), 최소 id 로 매칭", key, duplicates.size, duplicates.map { it.id })
        }
        return grouped.mapValues { (_, duplicates) -> duplicates.minBy { it.id } }
    }

    @Transactional
    fun createIncomplete(koreanNames: Set<String>): Map<String, Food> {
        if (koreanNames.isEmpty()) return emptyMap()

        upsertIncomplete(koreanNames.map { Food.incomplete(it) })

        val resolved = foodRepository.findByKoreanNameIn(koreanNames).associateBy { it.koreanName }
        val unresolved = koreanNames - resolved.keys
        if (unresolved.isNotEmpty()) {
            log.warn("미완성 음식 등록 누락 — 소프트 삭제된 동명 음식이 있습니다: {}", unresolved)
        }
        return resolved
    }

    fun findSubstanceCatalog(codes: Set<AvoidanceSubstanceCode>) =
        if (codes.isEmpty()) emptyList() else avoidanceSubstanceRepository.findByCodeIn(codes)

    fun avoidedCodeNames(memberId: Long?): Set<String> =
        avoidedSubstanceHelper.avoidedCodes(memberId).map { it.name }.toSet()

    private fun foodPage(rows: List<Food>, lang: LanguageCode, memberId: Long?): FoodPage {
        val hasNext = rows.size > PAGE_SIZE
        val items = rows.take(PAGE_SIZE)
        val nextCursor = if (hasNext) items.last().id else null

        val userAvoidedCodes = avoidedCodeNames(memberId)

        return FoodPage(
            items = items.map { FoodSummaryView.from(it, lang, userAvoidedCodes) },
            nextCursor = nextCursor,
            hasNext = hasNext,
        )
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
            query.setParameter(index * 4 + 1, food.koreanName)
            query.setParameter(index * 4 + 2, food.description)
            query.setParameter(index * 4 + 3, food.spiciness)
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
        return foodRepository.findByIdIn(ids).sortedByDescending { it.id }
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}
