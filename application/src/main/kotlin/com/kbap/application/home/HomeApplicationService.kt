package com.kbap.application.home

import com.kbap.domain.food.dto.FoodSummaryView
import com.kbap.application.home.dto.AvoidedSubstanceView
import com.kbap.application.home.dto.HomeResult
import com.kbap.domain.avoidance.AvoidanceCatalogService
import com.kbap.domain.food.FoodService
import com.kbap.core.lang.LanguageCode
import com.kbap.domain.member.MemberService
import com.kbap.domain.scan.ScanService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class HomeApplicationService(
    private val memberService: MemberService,
    private val foodService: FoodService,
    private val scanService: ScanService,
    private val avoidanceCatalogService: AvoidanceCatalogService,
) {
    @Transactional(readOnly = true)
    fun getHome(memberId: Long?, lang: LanguageCode): HomeResult {
        val member = memberId?.let { memberService.getMemberOrNull(it) }
        val avoidedCodes = memberService.getAvoidedCodes(member?.id)
        val avoidedRefs = avoidedCodes.map { it.name }.toSet()

        return HomeResult(
            avoidedSubstances = avoidanceCatalogService.getSubstancesByCodes(avoidedCodes)
                .map { AvoidedSubstanceView(code = it.code.name, name = it.displayName(lang)) },
            popularFoods = foodService.getRandomReadyFoods(POPULAR_SIZE)
                .map { FoodSummaryView.from(it, lang, avoidedRefs, foodService.resolveImageUrl(it)) },
            recentScans = member?.id?.let { id ->
                val recentIds = scanService.getRecentReadyFoodIds(id, RECENT_SCAN_SIZE)
                val foodsById = foodService.getReadyFoodsByIds(recentIds).associateBy { it.id }
                recentIds.mapNotNull { foodsById[it] }
                    .map { FoodSummaryView.from(it, lang, avoidedRefs, foodService.resolveImageUrl(it)) }
            }.orEmpty(),
        )
    }

    companion object {
        const val POPULAR_SIZE = 5
        const val RECENT_SCAN_SIZE = 10
    }
}
