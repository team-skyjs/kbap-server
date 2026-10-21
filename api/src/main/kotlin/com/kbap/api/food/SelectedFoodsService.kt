package com.kbap.api.food

import com.kbap.api.bookmark.BookmarkService
import com.kbap.api.review.ReviewService
import com.kbap.common.domain.LanguageCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SelectedFoodsService(
    private val foodService: FoodService,
    private val bookmarkService: BookmarkService,
    private val reviewService: ReviewService,
) {
    @Transactional(readOnly = true)
    fun getSelectedFoods(ids: List<Long>, lang: LanguageCode, memberId: Long?): SelectedFoodsResponse {
        val summaries = foodService.getReadyFoodSummariesInOrder(memberId, lang, ids)
        val availableIds = summaries.map { it.foodId }.toSet()
        val bookmarkedIds = bookmarkService.getBookmarkedFoodIds(memberId, availableIds)
        val ratings = reviewService.getFoodRatings(availableIds.toList())
        return SelectedFoodsResponse(
            items = summaries.map { FoodSummaryResponse.from(it, it.foodId in bookmarkedIds, ratings[it.foodId]) },
            unavailableFoodIds = ids.filterNot { it in availableIds },
        )
    }
}
