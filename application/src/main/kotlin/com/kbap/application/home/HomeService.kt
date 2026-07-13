package com.kbap.application.home

import com.kbap.core.id.MemberId
import com.kbap.application.food.dto.FoodSummaryView
import com.kbap.application.food.AvoidedSubstanceProvider
import com.kbap.application.home.dto.AvoidedSubstanceView
import com.kbap.application.home.dto.HomeResult
import com.kbap.domain.avoidance.AvoidanceSubstanceJpaRepository
import com.kbap.application.food.FoodService
import com.kbap.core.lang.LanguageCode
import com.kbap.domain.member.MemberJpaRepository
import com.kbap.domain.member.MemberStatus
import com.kbap.domain.scan.ScanHistoryJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class HomeService(
    private val memberRepository: MemberJpaRepository,
    private val foodService: FoodService,
    private val scanHistoryRepository: ScanHistoryJpaRepository,
    private val avoidanceSubstanceRepository: AvoidanceSubstanceJpaRepository,
    private val avoidedSubstanceProvider: AvoidedSubstanceProvider,
) {
    @Transactional(readOnly = true)
    fun getHome(memberId: Long?): HomeResult {
        val member = memberId?.let { memberRepository.findByIdAndMemberStatus(it, MemberStatus.ACTIVE) }
        val lang = member?.profile?.appLanguage ?: LanguageCode.EN
        val avoidedCodes = avoidedSubstanceProvider.avoidedCodes(member?.id)
        val avoidedRefs = avoidedCodes.map { it.name }.toSet()

        return HomeResult(
            avoidedSubstances = (if (avoidedCodes.isEmpty()) emptyList() else avoidanceSubstanceRepository.findByCodeIn(avoidedCodes))
                .map { AvoidedSubstanceView(code = it.code.name, name = it.displayName(lang)) },
            popularFoods = foodService.findRandomReady(POPULAR_SIZE)
                .map { FoodSummaryView.from(it, lang, avoidedRefs) },
            recentScans = member?.id?.let { id ->
                val recentIds = scanHistoryRepository.findRecentReadyFoodIds(id, RECENT_SCAN_SIZE)
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
