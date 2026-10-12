package com.kbap.api.home

import com.kbap.api.bookmark.BookmarkService
import com.kbap.api.food.FoodService
import com.kbap.api.food.PopularFoodIdCache
import com.kbap.api.ingredient.IngredientService
import com.kbap.api.member.MemberService
import com.kbap.api.review.ReviewService
import com.kbap.common.domain.LanguageCode
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Service
class HomeService(
    private val memberService: MemberService,
    private val ingredientService: IngredientService,
    private val foodService: FoodService,
    private val bookmarkService: BookmarkService,
    private val reviewService: ReviewService,
    private val popularFoodIdCache: PopularFoodIdCache,
    transactionManager: PlatformTransactionManager,
) {
    private val readOnlyTransaction = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    fun getHome(memberId: Long?, lang: LanguageCode): HomeResult {
        val popularFoodIds = popularFoodIdCache.getPopularFoodIds(POPULAR_SIZE)
        return readOnlyTransaction.execute { assembleHome(memberId, lang, popularFoodIds) }!!
    }

    private fun assembleHome(memberId: Long?, lang: LanguageCode, popularFoodIds: List<Long>): HomeResult {
        val member = memberId?.let { memberService.getMember(it) }
        val avoidedSubstances = ingredientService.getAvoidedIngredients(member?.id, lang)
        val popularFoods = foodService.getPopularFoods(member?.id, lang, popularFoodIds)
        val mostReviewedFoods = foodService.getMostReviewedFoods(member?.id, lang, MOST_REVIEWED_SIZE)
        val recentScans = member
            ?.let { foodService.getRecentScannedFoods(it.id, lang, RECENT_SCAN_SIZE) }
            .orEmpty()
        val foodIds = (popularFoods + mostReviewedFoods + recentScans.map { it.summary }).map { it.foodId }
        val bookmarkedFoodIds = bookmarkService.getBookmarkedFoodIds(member?.id, foodIds)
        val ratings = reviewService.getFoodRatings(foodIds)

        return HomeResult(
            avoidedSubstances = avoidedSubstances,
            popularFoods = popularFoods,
            mostReviewedFoods = mostReviewedFoods,
            recentScans = recentScans,
            bookmarkedFoodIds = bookmarkedFoodIds,
            ratings = ratings,
        )
    }

    companion object {
        const val POPULAR_SIZE = 10
        const val RECENT_SCAN_SIZE = 10
        const val MOST_REVIEWED_SIZE = 10
    }
}
