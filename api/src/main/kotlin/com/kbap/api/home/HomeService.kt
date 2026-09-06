package com.kbap.api.home

import com.kbap.api.food.FoodSummaryView
import com.kbap.common.domain.ingredient.IngredientJpaRepository
import com.kbap.api.food.FoodService
import com.kbap.common.domain.LanguageCode
import com.kbap.api.member.MemberService
import com.kbap.api.scan.ScanService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneId

@Service
class HomeService(
    private val memberService: MemberService,
    private val foodService: FoodService,
    private val scanService: ScanService,
    private val ingredientRepository: IngredientJpaRepository,
) {
    @Transactional(readOnly = true)
    fun getHome(memberId: Long?, lang: LanguageCode): HomeResult {
        val member = memberId?.let { memberService.getMemberOrNull(it) }
        val avoidedCodes = memberService.getAvoidedCodes(member?.id)
        val avoidedRefs = avoidedCodes.map { it.name }.toSet()

        return HomeResult(
            avoidedSubstances = (if (avoidedCodes.isEmpty()) emptyList() else ingredientRepository.findByCodeIn(avoidedCodes))
                .map { AvoidedSubstanceView(code = it.code.name, name = it.displayName(lang)) },
            popularFoods = foodService.getRandomReadyFoods(POPULAR_SIZE)
                .map { FoodSummaryView.from(it, lang, avoidedRefs, foodService.resolveImageUrl(it)) },
            recentScans = member?.id?.let { id ->
                val recentScans = scanService.getRecentReadyScans(id, RECENT_SCAN_SIZE)
                val foodsById = foodService.getReadyFoodsByIds(recentScans.map { it.foodId }).associateBy { it.id }
                recentScans.mapNotNull { scan ->
                    foodsById[scan.foodId]?.let { food ->
                        HomeResult.RecentScanView(
                            food = FoodSummaryView.from(food, lang, avoidedRefs, foodService.resolveImageUrl(food)),
                            scannedAt = scan.lastScannedAt.atZone(ZoneId.systemDefault()).toInstant(),
                        )
                    }
                }
            }.orEmpty(),
        )
    }

    companion object {
        const val POPULAR_SIZE = 5
        const val RECENT_SCAN_SIZE = 10
    }
}
