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
        val member = memberId?.let { memberService.getMember(it) }
        val avoidedSubstances = ingredientService.getAvoidedIngredients(member?.id, lang)
        val popularFoods = foodService.getPopularFoods(member?.id, lang, POPULAR_SIZE)
        val mostReviewedFoods = foodService.getMostReviewedFoods(member?.id, lang, MOST_REVIEWED_SIZE)
        val recentScans = member
            ?.let { foodService.getRecentScannedFoods(it.id, lang, RECENT_SCAN_SIZE) }
            .orEmpty()

        return HomeResult(
            avoidedSubstances = avoidedSubstances,
            popularFoods = popularFoods,
            mostReviewedFoods = mostReviewedFoods,
            recentScans = recentScans,
        )
    }

    companion object {
        const val POPULAR_SIZE = 10
        const val RECENT_SCAN_SIZE = 10
        const val MOST_REVIEWED_SIZE = 10
    }
}
