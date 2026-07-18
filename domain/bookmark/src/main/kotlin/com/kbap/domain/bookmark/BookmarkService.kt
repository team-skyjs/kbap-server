package com.kbap.domain.bookmark

import com.kbap.core.lang.LanguageCode
import com.kbap.domain.bookmark.dto.BookmarkPage
import com.kbap.domain.bookmark.model.Bookmark
import com.kbap.domain.food.FoodService
import com.kbap.domain.food.dto.FoodSummaryView
import com.kbap.domain.member.MemberService
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BookmarkService internal constructor(
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
    fun getBookmarkPage(memberId: Long, lang: String?, cursor: Long?): BookmarkPage {
        val rows = bookmarkRepository.findPage(memberId, cursor, PageRequest.of(0, PAGE_SIZE + 1))

        val hasNext = rows.size > PAGE_SIZE
        val page = rows.take(PAGE_SIZE)
        val nextCursor = if (hasNext) page.last().id else null

        val orderedFoodIds = page.map { it.foodId }
        val foodsById = foodService.getReadyFoodsByIds(orderedFoodIds).associateBy { it.id }
        val languageCode = LanguageCode.from(lang)
        val avoidedCodes = memberService.getAvoidedCodes(memberId).map { it.name }.toSet()

        val items = orderedFoodIds.mapNotNull { foodId ->
            foodsById[foodId]?.let { FoodSummaryView.from(it, languageCode, avoidedCodes, foodService.resolveImageUrl(it)) }
        }

        return BookmarkPage(items = items, nextCursor = nextCursor, hasNext = hasNext)
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}
