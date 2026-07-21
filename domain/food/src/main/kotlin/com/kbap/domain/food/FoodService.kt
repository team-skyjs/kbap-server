package com.kbap.domain.food

import com.kbap.domain.food.model.Food
import com.kbap.domain.food.dto.BrowseFoodsInput
import com.kbap.domain.food.dto.FoodPage
import com.kbap.domain.food.dto.FoodSummaryView
import com.kbap.domain.food.dto.GetFoodDetailInput
import com.kbap.domain.food.dto.GetFoodDetailResult
import com.kbap.domain.food.dto.SearchFoodsInput
import com.kbap.core.error.ErrorCode
import com.kbap.core.error.BusinessException
import com.kbap.core.image.ImageUrls
import com.kbap.core.lang.LanguageCode
import com.kbap.core.menu.KoreanMenuNameNormalizer
import com.kbap.domain.avoidance.model.AvoidanceSubstanceCode
import com.kbap.domain.avoidance.AvoidanceCatalogService
import com.kbap.domain.member.MemberService
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FoodService internal constructor(
    private val foodRepository: FoodJpaRepository,
    private val avoidanceCatalogService: AvoidanceCatalogService,
    private val memberService: MemberService,
    private val entityManager: EntityManager,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun getFoodPage(input: BrowseFoodsInput): FoodPage =
        foodPage(getFoods(input.cursor, PAGE_SIZE + 1), input.lang, input.memberId)

    @Transactional(readOnly = true)
    fun searchFoodPage(input: SearchFoodsInput): FoodPage =
        foodPage(getFoodsByKeyword(input.keyword, input.lang, input.cursor, PAGE_SIZE + 1), input.lang, input.memberId)

    @Transactional(readOnly = true)
    internal fun getFoods(cursor: Long?, size: Int): List<Food> =
        loadDescending(foodRepository.findFoodPageIds(cursor, PageRequest.of(0, size)))

    @Transactional(readOnly = true)
    internal fun getFoodsByKeyword(keyword: String, lang: LanguageCode, cursor: Long?, size: Int): List<Food> {
        val jsonPath = if (lang == LanguageCode.KO) null else "$.\"${lang.code}\""
        return loadDescending(foodRepository.searchFoodPageIds(escapeLikeWildcards(keyword), jsonPath, cursor, size))
    }

    @Transactional(readOnly = true)
    fun getDetail(input: GetFoodDetailInput): GetFoodDetailResult {
        val lang = input.lang
        val food = getReadyFood(input.foodId)

        val userAvoidedCodes = avoidedCodeNames(input.memberId)
        val orderedSubstances = food.avoidanceSubstancesByProbability()
            .filter { it.substanceCode in userAvoidedCodes }
        val codes = orderedSubstances.map { AvoidanceSubstanceCode.valueOf(it.substanceCode) }.toSet()
        val catalog = avoidanceCatalogService.getSubstancesByCodes(codes).associateBy { it.code }

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

        return GetFoodDetailResult(
            name = foodName,
            koreanName = food.koreanName().takeIf { it != foodName },
            imageRef = resolveImageUrl(food),
            description = description,
            spiciness = food.spiciness,
            overallRiskStatus = food.overallRisk(userAvoidedCodes),
            avoidanceSubstances = avoidanceSubstances,
        )
    }

    @Transactional(readOnly = true)
    fun getReadyFood(id: Long): Food =
        foodRepository.findByIdIn(listOf(id))
            .firstOrNull()
            ?.takeIf { it.isReady() }
            ?: throw BusinessException(ErrorCode.FOOD_NOT_FOUND)

    @Transactional(readOnly = true)
    fun getRandomReadyFoods(size: Int): List<Food> {
        val ids = foodRepository.findRandomReadyIds(size)
        if (ids.isEmpty()) return emptyList()
        return foodRepository.findByIdIn(ids)
    }

    @Transactional(readOnly = true)
    fun getReadyFoodsByIds(ids: List<Long>): List<Food> {
        if (ids.isEmpty()) return emptyList()
        return foodRepository.findByIdIn(ids)
            .sortedBy { it.id }
            .filter { it.isReady() }
    }

    @Transactional(readOnly = true)
    fun getFoodsByKoreanMatchKeys(keys: Set<String>): Map<String, Food> {
        if (keys.isEmpty()) return emptyMap()
        // ponytail: 전 음식 풀로드 매칭 — 수만 건 규모가 되면 캐시 도입
        val matched = foodRepository.findAll()
            .groupBy { KoreanMenuNameNormalizer.matchKey(it.koreanName) }
            .filterKeys { it in keys }
        matched.filterValues { it.size > 1 }.forEach { (key, duplicates) ->
            log.warn("동음이의 음식 매칭 — key={} 에 {} 개 음식({}), 최소 id 로 매칭", key, duplicates.size, duplicates.map { it.id })
        }
        return matched.mapValues { (_, duplicates) -> duplicates.minBy { it.id } }
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

    private fun avoidedCodeNames(memberId: Long?): Set<String> =
        memberService.getAvoidedCodes(memberId).map { it.name }.toSet()

    private fun foodPage(rows: List<Food>, lang: LanguageCode, memberId: Long?): FoodPage {
        val hasNext = rows.size > PAGE_SIZE
        val items = rows.take(PAGE_SIZE)
        val nextCursor = if (hasNext) items.last().id else null

        val userAvoidedCodes = avoidedCodeNames(memberId)

        return FoodPage(
            items = items.map { FoodSummaryView.from(it, lang, userAvoidedCodes, resolveImageUrl(it)) },
            nextCursor = nextCursor,
            hasNext = hasNext,
        )
    }

    fun resolveImageUrl(food: Food): String? = ImageUrls.resolve(imagePublicBaseUrl, food.imageRef)

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
