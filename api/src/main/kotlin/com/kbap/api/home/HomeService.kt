package com.kbap.api.home

import com.kbap.api.bookmark.BookmarkService
import com.kbap.api.food.FoodService
import com.kbap.api.ingredient.IngredientService
import com.kbap.api.member.MemberService
import com.kbap.api.review.ReviewService
import com.kbap.common.domain.LanguageCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class HomeService(
    private val memberService: MemberService,
    private val ingredientService: IngredientService,
    private val foodService: FoodService,
    private val bookmarkService: BookmarkService,
    private val reviewService: ReviewService,
) {
    @Transactional(readOnly = true)
    fun getHome(memberId: Long?, lang: LanguageCode): HomeResult {
        val member = memberId?.let { memberService.getMember(it) }
        val avoidedSubstances = ingredientService.getAvoidedIngredients(member?.id, lang)
        val popularFoods = foodService.getPopularFoods(member?.id, lang, POPULAR_SIZE)
        val mostReviewedFoods = foodService.getMostReviewedFoods(member?.id, lang, MOST_REVIEWED_SIZE)
        val recentScans = member
            ?.let { foodService.getRecentScannedFoods(it.id, lang, RECENT_SCAN_SIZE) }
            .orEmpty()
        val foodIds = (popularFoods + mostReviewedFoods + recentScans.map { it.summary }).map { it.foodId }

        return HomeResult(
            avoidedSubstances = avoidedSubstances,
            popularFoods = popularFoods,
            mostReviewedFoods = mostReviewedFoods,
            recentScans = recentScans,
            bookmarkedFoodIds = bookmarkService.getBookmarkedFoodIds(member?.id, foodIds),
            ratings = reviewService.getFoodRatings(foodIds),
        )
    }

    companion object {
        const val POPULAR_SIZE = 10
        const val RECENT_SCAN_SIZE = 10
        const val MOST_REVIEWED_SIZE = 10
    }
}
