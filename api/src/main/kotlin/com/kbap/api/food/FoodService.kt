package com.kbap.api.food

import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.error.BusinessException
import com.kbap.common.util.ImageUrls
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.domain.ingredient.IngredientJpaRepository
import com.kbap.common.domain.scan.ScanHistoryJpaRepository
import com.kbap.api.member.MemberService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FoodService(
    private val foodRepository: FoodJpaRepository,
    private val outboxRepository: FoodContentOutboxJpaRepository,
    private val ingredientRepository: IngredientJpaRepository,
    private val scanHistoryRepository: ScanHistoryJpaRepository,
    private val memberService: MemberService,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun getFoodPage(input: BrowseFoodsInput): FoodPage =
        foodPage(getFoods(input.cursor, PAGE_SIZE + 1), input.lang, input.memberId)

    @Transactional(readOnly = true)
    fun searchFoodPage(input: SearchFoodsInput): FoodPage =
        if (input.scope == FoodSearchScope.SCANNED) {
            val rows = getScannedFoods(requireNotNull(input.memberId), input.keyword, input.lang)
            FoodPage(items = summaryViews(rows, input.lang, input.memberId), nextCursor = null, hasNext = false)
        } else {
            foodPage(getFoodsByKeyword(input.keyword, input.lang, input.cursor, PAGE_SIZE + 1), input.lang, input.memberId)
        }

    @Transactional(readOnly = true)
    internal fun getFoods(cursor: Long?, size: Int): List<Food> =
        loadDescending(foodRepository.findFoodPageIds(cursor, PageRequest.of(0, size)))

    @Transactional(readOnly = true)
    internal fun getFoodsByKeyword(keyword: String, lang: LanguageCode, cursor: Long?, size: Int): List<Food> =
        loadDescending(
            foodRepository.searchFoodPageIds(escapeLikeWildcards(keyword), translationJsonPath(lang), cursor, size),
        )

    private fun getScannedFoods(memberId: Long, keyword: String, lang: LanguageCode): List<Food> {
        val ids = scanHistoryRepository.findScannedFoodIds(memberId, escapeLikeWildcards(keyword), translationJsonPath(lang))
        return loadInGivenOrder(ids)
    }

    @Transactional(readOnly = true)
    fun getScannedFoodPage(memberId: Long, lang: LanguageCode, cursor: Long?): FoodPage {
        val cursorLastScannedAt = cursor?.let {
            scanHistoryRepository.findLastScannedAt(memberId, it)
                ?: throw BusinessException(ErrorCode.INVALID_CURSOR)
        }
        val ids = scanHistoryRepository.findScannedFoodPageIds(memberId, cursorLastScannedAt, cursor, PAGE_SIZE + 1)
        return foodPage(loadInGivenOrder(ids), lang, memberId)
    }

    private fun loadInGivenOrder(ids: List<Long>): List<Food> {
        if (ids.isEmpty()) return emptyList()
        val foodsById = foodRepository.findByIdIn(ids).associateBy { it.id }
        return ids.mapNotNull { foodsById[it] }
    }

    private fun translationJsonPath(lang: LanguageCode): String? =
        if (lang == LanguageCode.KO) null else "$.\"${lang.code}\""

    @Transactional(readOnly = true)
    fun getDetail(input: GetFoodDetailInput): GetFoodDetailResult {
        val lang = input.lang
        val food = getReadyFood(input.foodId)

        val allIngredients = food.ingredientsByProbability()
        val codes = allIngredients.map { IngredientCode.valueOf(it.code) }.toSet()
        val catalog = (if (codes.isEmpty()) emptyList() else ingredientRepository.findByCodeIn(codes)).associateBy { it.code }

        val userAvoidedCodes = avoidedCodeNames(input.memberId)
        val foodName = food.displayName(lang)

        return GetFoodDetailResult(
            name = foodName,
            koreanName = food.displayName(LanguageCode.KO).takeIf { it != foodName },
            imageRef = resolveImageUrl(food),
            description = food.description(lang),
            spiciness = food.spiciness,
            overallRiskStatus = if (input.memberId == null) null else food.overallRisk(userAvoidedCodes),
            ingredients = allIngredients.map { ingredient ->
                GetFoodDetailResult.IngredientView(
                    code = ingredient.code,
                    name = catalog.getValue(IngredientCode.valueOf(ingredient.code)).displayName(lang),
                    inclusionPercent = ingredient.inclusionPercent,
                )
            },
            avoidedIngredients = if (input.memberId == null) {
                null
            } else {
                allIngredients
                    .filter { it.code in userAvoidedCodes }
                    .map { GetFoodDetailResult.AvoidedIngredientView(code = it.code, riskStatus = it.riskLevel()) }
            },
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
    fun createIncomplete(displayNamesByMatchKey: Map<String, String>): Map<String, Food> {
        if (displayNamesByMatchKey.isEmpty()) return emptyMap()
        return upsertAndResolve(displayNamesByMatchKey)
    }

    private fun upsertAndResolve(displayNamesByMatchKey: Map<String, String>): Map<String, Food> {
        foodRepository.upsertIncomplete(displayNamesByMatchKey.map { (matchKey, displayName) -> Food.failed(matchKey, displayName) })

        val matchKeys = displayNamesByMatchKey.keys
        val resolved = foodRepository.findByKoreanNameIn(matchKeys).associateBy { it.koreanName }
        val unresolved = matchKeys - resolved.keys
        if (unresolved.isNotEmpty()) {
            log.warn("미완성 음식 등록 누락 — 소프트 삭제된 동명 음식이 있습니다: {}", unresolved)
        }
        enqueueContentRequests(resolved.values)
        return resolved
    }

    private fun enqueueContentRequests(foods: Collection<Food>) {
        if (foods.isEmpty()) return
        val alreadyPending = outboxRepository
            .findByFoodIdInAndOutboxStatus(foods.map { it.id }, FoodContentOutboxStatus.PENDING)
            .map { it.foodId }
            .toSet()
        outboxRepository.saveAll(
            foods.filterNot { it.id in alreadyPending }.map { FoodContentOutbox.pending(it.id, it.displayName) },
        )
    }

    private fun avoidedCodeNames(memberId: Long?): Set<String> =
        memberService.getAvoidedCodes(memberId).map { it.name }.toSet()

    private fun foodPage(rows: List<Food>, lang: LanguageCode, memberId: Long?): FoodPage {
        val hasNext = rows.size > PAGE_SIZE
        val items = rows.take(PAGE_SIZE)
        val nextCursor = if (hasNext) items.last().id else null

        return FoodPage(
            items = summaryViews(items, lang, memberId),
            nextCursor = nextCursor,
            hasNext = hasNext,
        )
    }

    private fun summaryViews(rows: List<Food>, lang: LanguageCode, memberId: Long?): List<FoodSummaryView> {
        val userAvoidedCodes = avoidedCodeNames(memberId)
        return rows.map { FoodSummaryView.from(it, lang, userAvoidedCodes, resolveImageUrl(it)) }
    }

    fun resolveImageUrl(food: Food): String? = ImageUrls.resolve(imagePublicBaseUrl, food.imageRef)

    fun resolveImageUrlOrDefault(food: Food?): String =
        food?.let { resolveImageUrl(it) }
            ?: requireNotNull(ImageUrls.resolve(imagePublicBaseUrl, DEFAULT_FOOD_IMAGE_PATH))

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
        const val DEFAULT_FOOD_IMAGE_PATH = "images/webp/default_miss_food/food_not_found.png"
    }
}
