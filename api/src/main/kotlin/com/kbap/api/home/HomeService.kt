package com.kbap.api.home

import com.kbap.api.food.FoodService
import com.kbap.api.ingredient.IngredientService
import com.kbap.api.member.MemberService
import com.kbap.common.domain.LanguageCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class HomeService(
    private val memberService: MemberService,
    private val ingredientService: IngredientService,
    private val foodService: FoodService,
) {
    @Transactional(readOnly = true)
    fun getHome(memberId: Long?, lang: LanguageCode): HomeResult {
        val activeMemberId = memberId?.let { memberService.getMemberOrNull(it)?.id }
        val avoidedSubstances = ingredientService.getAvoidedIngredients(activeMemberId, lang)
        val popularFoods = foodService.getPopularFoods(activeMemberId, lang, POPULAR_SIZE)
        val mostReviewedFoods = foodService.getMostReviewedFoods(activeMemberId, lang, MOST_REVIEWED_SIZE)
        val recentScans = activeMemberId
            ?.let { foodService.getRecentScannedFoods(it, lang, RECENT_SCAN_SIZE) }
            .orEmpty()

        return HomeResult(
            avoidedSubstances = avoidedSubstances,
            popularFoods = popularFoods,
            mostReviewedFoods = mostReviewedFoods,
            recentScans = recentScans,
        )
    }

    companion object {
        const val POPULAR_SIZE = 5
        const val RECENT_SCAN_SIZE = 10
        const val MOST_REVIEWED_SIZE = 10
    }
}
