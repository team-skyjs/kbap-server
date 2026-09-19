package com.kbap.api.bookmark

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.bookmark.BookmarkJpaRepository
import com.kbap.common.domain.bookmark.model.Bookmark
import com.kbap.api.food.FoodService
import com.kbap.api.food.FoodSummaryView
import com.kbap.api.food.RiskCandidate
import com.kbap.common.domain.food.model.RiskLevel
import com.kbap.api.member.MemberService
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BookmarkService(
    private val foodService: FoodService,
    private val memberService: MemberService,
    private val bookmarkRepository: BookmarkJpaRepository,
) {
    @Transactional
    fun bookmark(memberId: Long, foodId: Long) {
        foodService.getReadyFood(foodId)
        if (bookmarkRepository.findByMemberIdAndFoodId(memberId, foodId) != null) return
        bookmarkRepository.save(Bookmark(memberId = memberId, foodId = foodId))
    }

    @Transactional
    fun unbookmark(memberId: Long, foodId: Long) {
        bookmarkRepository.findByMemberIdAndFoodId(memberId, foodId)?.delete()
    }

    @Transactional(readOnly = true)
    fun getBookmarkedFoodIds(memberId: Long?, foodIds: Collection<Long>): Set<Long> {
        if (memberId == null || foodIds.isEmpty()) return emptySet()
        return bookmarkRepository.findByMemberIdAndFoodIdIn(memberId, foodIds)
            .map { it.foodId }
            .toSet()
    }

    @Transactional(readOnly = true)
    fun getBookmarkPage(memberId: Long, lang: LanguageCode, cursor: Long?, risks: Set<RiskLevel>? = null): BookmarkPage {
        val avoidedCodes = memberService.getAvoidedCodes(memberId).map { it.name }.toSet()

        if (risks == null) {
            val rows = bookmarkRepository.findPage(memberId, cursor, PageRequest.of(0, PAGE_SIZE + 1))
            val hasNext = rows.size > PAGE_SIZE
            val page = rows.take(PAGE_SIZE)
            return bookmarkPage(page, if (hasNext) page.last().id else null, hasNext, lang, avoidedCodes)
        }

        val filtered = foodService.collectRiskFiltered(cursor, avoidedCodes, risks) { c, size ->
            val bookmarks = bookmarkRepository.findPage(memberId, c, PageRequest.of(0, size))
            val foodsById = foodService.getReadyFoodsByIds(bookmarks.map { it.foodId }).associateBy { it.id }
            bookmarks.map { RiskCandidate(it.id, foodsById[it.foodId]) }
        }
        val items = filtered.foods.map { FoodSummaryView.from(it, lang, avoidedCodes, foodService.resolveImageUrl(it)) }
        return BookmarkPage(items = items, nextCursor = filtered.nextCursor, hasNext = filtered.hasNext)
    }

    private fun bookmarkPage(
        page: List<Bookmark>,
        nextCursor: Long?,
        hasNext: Boolean,
        lang: LanguageCode,
        avoidedCodes: Set<String>,
    ): BookmarkPage {
        val orderedFoodIds = page.map { it.foodId }
        val foodsById = foodService.getReadyFoodsByIds(orderedFoodIds).associateBy { it.id }
        val items = orderedFoodIds.mapNotNull { foodId ->
            foodsById[foodId]?.let { FoodSummaryView.from(it, lang, avoidedCodes, foodService.resolveImageUrl(it)) }
        }
        return BookmarkPage(items = items, nextCursor = nextCursor, hasNext = hasNext)
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}
