package com.meogo.application.client.home

import com.meogo.application.client.food.dto.FoodSummaryView
import com.meogo.application.client.food.usecase.AvoidedSubstanceProvider
import com.meogo.application.client.home.dto.AvoidedSubstanceView
import com.meogo.application.client.home.dto.HomeResult
import com.meogo.core.avoidance.AvoidanceSubstanceRepository
import com.meogo.core.food.AvoidanceSubstanceCodeRef
import com.meogo.core.food.FoodRepository
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.member.MemberRepository
import com.meogo.core.scan.ScanHistoryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class HomeQueryUseCase(
    private val memberRepository: MemberRepository,
    private val foodRepository: FoodRepository,
    private val scanHistoryRepository: ScanHistoryRepository,
    private val avoidanceSubstanceRepository: AvoidanceSubstanceRepository,
    private val avoidedSubstanceProvider: AvoidedSubstanceProvider,
) {
    @Transactional(readOnly = true)
    fun getHome(memberId: Long?): HomeResult {
        val member = memberId?.let { memberRepository.findById(it) }
        val lang = member?.profile?.appLanguage ?: LanguageCode.EN
        val avoidedCodes = avoidedSubstanceProvider.avoidedCodes(member?.id)
        val avoidedRefs = avoidedCodes.map { AvoidanceSubstanceCodeRef(it.name) }.toSet()

        return HomeResult(
            avoidedSubstances = avoidanceSubstanceRepository.findByCodes(avoidedCodes)
                .map { AvoidedSubstanceView(code = it.code.name, name = it.displayName(lang)) },
            popularFoods = foodRepository.findRandomReady(POPULAR_SIZE)
                .map { FoodSummaryView.from(it, lang, avoidedRefs) },
            recentScans = member?.id?.let { id ->
                val recentIds = scanHistoryRepository.findRecentReadyFoodIds(id, RECENT_SCAN_SIZE)
                val foodsById = foodRepository.findAllReadyByIds(recentIds).associateBy { it.id }
                recentIds.mapNotNull { foodsById[it] }.map { FoodSummaryView.from(it, lang, avoidedRefs) }
            }.orEmpty(),
        )
    }

    companion object {
        const val POPULAR_SIZE = 5
        const val RECENT_SCAN_SIZE = 10
    }
}
