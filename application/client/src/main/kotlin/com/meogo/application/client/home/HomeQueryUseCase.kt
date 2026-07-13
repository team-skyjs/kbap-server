package com.meogo.application.client.home

import com.meogo.application.client.food.dto.FoodSummaryView
import com.meogo.application.client.food.usecase.AvoidedSubstanceProvider
import com.meogo.application.client.home.dto.AvoidedSubstanceView
import com.meogo.application.client.home.dto.HomeResult
import com.meogo.domain.avoidance.AvoidanceSubstanceService
import com.meogo.domain.food.AvoidanceSubstanceCodeRef
import com.meogo.domain.food.FoodService
import com.meogo.core.lang.LanguageCode
import com.meogo.domain.member.MemberService
import com.meogo.domain.scan.ScanHistoryService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class HomeQueryUseCase(
    private val memberService: MemberService,
    private val foodService: FoodService,
    private val scanHistoryService: ScanHistoryService,
    private val avoidanceSubstanceService: AvoidanceSubstanceService,
    private val avoidedSubstanceProvider: AvoidedSubstanceProvider,
) {
    @Transactional(readOnly = true)
    fun getHome(memberId: Long?): HomeResult {
        val member = memberId?.let { memberService.findById(it) }
        val lang = member?.profile?.appLanguage ?: LanguageCode.EN
        val avoidedCodes = avoidedSubstanceProvider.avoidedCodes(member?.id)
        val avoidedRefs = avoidedCodes.map { AvoidanceSubstanceCodeRef(it.name) }.toSet()

        return HomeResult(
            avoidedSubstances = avoidanceSubstanceService.findByCodes(avoidedCodes)
                .map { AvoidedSubstanceView(code = it.code.name, name = it.displayName(lang)) },
            popularFoods = foodService.findRandomReady(POPULAR_SIZE)
                .map { FoodSummaryView.from(it, lang, avoidedRefs) },
            recentScans = member?.id?.let { id ->
                val recentIds = scanHistoryService.findRecentReadyFoodIds(id, RECENT_SCAN_SIZE)
                val foodsById = foodService.findAllReadyByIds(recentIds).associateBy { it.id }
                recentIds.mapNotNull { foodsById[it] }.map { FoodSummaryView.from(it, lang, avoidedRefs) }
            }.orEmpty(),
        )
    }

    companion object {
        const val POPULAR_SIZE = 5
        const val RECENT_SCAN_SIZE = 10
    }
}
