package com.kbap.api.food

import com.kbap.api.bookmark.BookmarkService
import com.kbap.api.member.MemberService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.bookmark.BookmarkJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.RiskLevel
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class FoodListingPage(val items: List<FoodSummaryView>, val hasNext: Boolean, val nextCursor: String?)

@Service
class FoodListingService(
    private val foodRepository: FoodJpaRepository,
    private val bookmarkRepository: BookmarkJpaRepository,
    private val foodService: FoodService,
    private val bookmarkService: BookmarkService,
    private val memberService: MemberService,
) {
    @Transactional(readOnly = true)
    fun getFoodListingPage(
        lang: LanguageCode,
        sort: FoodListingSort,
        cursor: FoodListingCursor?,
        memberId: Long?,
        risks: Set<RiskLevel>?,
        saved: Boolean,
    ): FoodListingPage {
        val bookmarks = if (saved) bookmarkRepository.findListingEntries(requireNotNull(memberId)) else emptyList()
        val savedIds = bookmarks.map { it.foodId }.toSet()
        val names = foodRepository.findListingNames().filter { !saved || it.id in savedIds }
        val avoided = memberService.getAvoidance(memberId).codeNames
        val context = FoodListingCursor.fingerprint(listOf(
            FoodListingSort.COMPARATOR_VERSION,
            lang.code,
            sort.value,
            saved.toString(),
            memberId.toString(),
            risks?.map { it.name }?.sorted()?.joinToString(",") ?: "all",
        ))
        val revision = FoodListingCursor.fingerprint(buildList {
            names.forEach {
                add(it.id.toString())
                add(it.name(lang))
                add(it.updatedAt.toString())
            }
            bookmarks.forEach {
                add(it.id.toString())
                add(it.foodId.toString())
            }
            addAll(avoided.sorted())
        })
        cursor?.verify(context, revision)
        fun encode(lastId: Long?): String? = lastId?.let { FoodListingCursor(context, revision, it).encode() }

        if (sort == FoodListingSort.DEFAULT) {
            if (saved) {
                val page = bookmarkService.getBookmarkPage(requireNotNull(memberId), lang, cursor?.lastId, risks)
                return FoodListingPage(page.items, page.hasNext, encode(page.nextCursor))
            }
            val page = foodService.getFoodPage(BrowseFoodsInput(cursor?.lastId, lang, memberId, risks))
            return FoodListingPage(page.items, page.hasNext, encode(page.nextCursor))
        }

        val orderedIds = sort.order(names, lang)
        val start = cursor?.let {
            val index = orderedIds.indexOf(it.lastId)
            if (index < 0) throw BusinessException(ErrorCode.INVALID_CURSOR)
            index + 1
        } ?: 0
        val scanLimit = if (risks == null) FoodService.PAGE_SIZE + 1 else FoodService.RISK_FILTER_BATCH_SIZE * FoodService.RISK_FILTER_MAX_BATCHES
        val candidates = orderedIds.drop(start).take(scanLimit)
        val foodsById = foodService.getReadyFoodsByIds(candidates).associateBy { it.id }
        val kept = mutableListOf<FoodSummaryView>()
        var lastScanned: Long? = null
        for (id in candidates) {
            lastScanned = id
            val food = foodsById[id] ?: continue
            if (risks != null && food.overallRisk(avoided) !in risks) continue
            if (kept.size == FoodService.PAGE_SIZE) {
                return FoodListingPage(kept, true, encode(kept.last().foodId))
            }
            kept += FoodSummaryView.from(food, lang, avoided, foodService.resolveImageUrl(food))
        }
        val hasNext = start + candidates.size < orderedIds.size
        return FoodListingPage(kept, hasNext, if (hasNext) encode(lastScanned) else null)
    }
}
