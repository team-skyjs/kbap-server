package com.kbap.application.home

import com.kbap.core.id.MemberId
import com.kbap.application.food.dto.FoodSummaryView
import com.kbap.application.food.usecase.AvoidedSubstanceProvider
import com.kbap.application.home.dto.AvoidedSubstanceView
import com.kbap.application.home.dto.HomeResult
import com.kbap.domain.avoidance.AvoidanceSubstanceService
import com.kbap.domain.food.AvoidanceSubstanceCodeRef
import com.kbap.domain.food.FoodService
import com.kbap.core.lang.LanguageCode
import com.kbap.domain.member.MemberService
import com.kbap.domain.scan.ScanHistoryService
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
                val recentIds = scanHistoryService.findRecentReadyFoodIds(MemberId(id), RECENT_SCAN_SIZE).map { it.value }
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
