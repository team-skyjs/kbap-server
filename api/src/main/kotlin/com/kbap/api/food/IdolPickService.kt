package com.kbap.api.food

import com.kbap.api.bookmark.BookmarkService
import com.kbap.api.review.ReviewService
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.FoodIdolVideoJpaRepository
import com.kbap.common.domain.food.model.FoodIdolVideo
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class IdolPickService(
    private val foodIdolVideoRepository: FoodIdolVideoJpaRepository,
    private val foodService: FoodService,
    private val bookmarkService: BookmarkService,
    private val reviewService: ReviewService,
) {
    @Transactional(readOnly = true)
    fun getIdolPicks(lang: LanguageCode, memberId: Long?): IdolPicksResponse {
        val videos = representativeVideosInOrder()
        val summaries = foodService.getReadyFoodSummariesInOrder(memberId, lang, videos.map { it.foodId })
        val foodIds = summaries.map { it.foodId }
        val bookmarkedIds = bookmarkService.getBookmarkedFoodIds(memberId, foodIds)
        val ratings = reviewService.getFoodRatings(foodIds)
        val videoByFoodId = videos.associateBy { it.foodId }
        return IdolPicksResponse(
            items = summaries.map {
                val video = videoByFoodId.getValue(it.foodId)
                IdolPickResponse(
                    food = FoodSummaryResponse.from(it, it.foodId in bookmarkedIds, ratings[it.foodId]),
                    video = IdolPickVideoResponse(url = video.videoUrl, platform = video.platform.name),
                )
            },
        )
    }

    private fun representativeVideosInOrder(): List<FoodIdolVideo> =
        foodIdolVideoRepository.findAll()
            .groupBy { it.foodId }
            .values
            .map { videos -> videos.minBy { it.position } }
            .sortedWith(
                compareByDescending<FoodIdolVideo> { it.hype }
                    .thenByDescending { it.publishedMonth }
                    .thenBy { it.foodId },
            )
}
