package com.kbap.common.domain.food

import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.dto.BrowseFoodsInput
import com.kbap.common.domain.food.dto.FoodPage
import com.kbap.common.domain.food.dto.FoodSummaryView
import com.kbap.common.domain.food.dto.GetFoodDetailInput
import com.kbap.common.domain.food.dto.GetFoodDetailResult
import com.kbap.common.domain.food.dto.SearchFoodsInput
import com.kbap.common.domain.food.dto.SeedIncompleteResult
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.image.ImageUrls
import com.kbap.common.core.lang.LanguageCode
import com.kbap.common.core.menu.KoreanMenuNameNormalizer
import com.kbap.common.domain.avoidance.model.AvoidanceSubstanceCode
import com.kbap.common.domain.avoidance.AvoidanceSubstanceJpaRepository
import com.kbap.common.domain.member.MemberService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FoodService(
    private val foodRepository: FoodJpaRepository,
    private val avoidanceSubstanceRepository: AvoidanceSubstanceJpaRepository,
    private val memberService: MemberService,
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
            .filter { it.code in userAvoidedCodes }
        val codes = orderedSubstances.map { AvoidanceSubstanceCode.valueOf(it.code) }.toSet()
        val catalog = (if (codes.isEmpty()) emptyList() else avoidanceSubstanceRepository.findByCodeIn(codes)).associateBy { it.code }

        val foodName = food.displayName(lang)
        val description = food.description(lang)

        val avoidanceSubstances = orderedSubstances.map { substance ->
            GetFoodDetailResult.AvoidanceSubstanceView(
                name = catalog.getValue(AvoidanceSubstanceCode.valueOf(substance.code)).displayName(lang),
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
    fun getFoodsByKoreanNames(names: Set<String>): Map<String, Food> {
        if (names.isEmpty()) return emptyMap()
        return foodRepository.findByKoreanNameIn(names).associateBy { it.koreanName }
    }

    @Transactional
    fun seedIncomplete(koreanNames: Set<String>): SeedIncompleteResult {
        val names = koreanNames
            .map { KoreanMenuNameNormalizer.matchKey(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        if (names.isEmpty()) return SeedIncompleteResult(requested = 0, created = 0, skipped = 0)

        val existing = foodRepository.findByKoreanNameIn(names).map { it.koreanName }.toSet()
        val newNames = names - existing
        val created = if (newNames.isEmpty()) 0 else upsertAndResolve(newNames).size
        return SeedIncompleteResult(
            requested = names.size,
            created = created,
            skipped = names.size - created,
        )
    }

    @Transactional
    fun createIncomplete(koreanNames: Set<String>): Map<String, Food> {
        if (koreanNames.isEmpty()) return emptyMap()
        return upsertAndResolve(koreanNames)
    }

    private fun upsertAndResolve(koreanNames: Set<String>): Map<String, Food> {
        foodRepository.upsertIncomplete(koreanNames.map { Food.incomplete(it) })

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
