package com.meogo.application.client.food.usecase

import com.meogo.application.client.food.dto.MenuPage
import com.meogo.application.client.food.dto.MenuSummaryView
import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.avoidance.AvoidanceSubstanceRepository
import com.meogo.core.food.AvoidanceSubstanceCodeRef
import com.meogo.core.food.Food
import com.meogo.core.kernel.lang.LanguageCode
import org.springframework.stereotype.Component

@Component
class MenuSummaryAssembler(
    private val avoidedSubstanceProvider: AvoidedSubstanceProvider,
    private val avoidanceSubstanceRepository: AvoidanceSubstanceRepository,
) {
    fun assemble(foods: List<Food>, lang: LanguageCode): List<MenuSummaryView> {
        val avoidedCodes = avoidedSubstanceProvider.avoidedCodes()
            .map { AvoidanceSubstanceCodeRef(it.name) }
            .toSet()
        val catalogCodes = catalogCodes(foods)

        return foods.map { food ->
            val resolvableAvoidedCodes = food.avoidanceSubstances
                .map { it.substanceCode }
                .filter { it in catalogCodes }
                .toSet()
            val localizedName = food.displayName(lang)
            MenuSummaryView(
                foodId = food.id!!,
                name = localizedName,
                koreanName = food.koreanName().takeIf { it != localizedName },
                imageRef = food.imageRef,
                spiciness = food.spiciness.value,
                overallRiskStatus = food.overallRisk(avoidedCodes intersect resolvableAvoidedCodes),
            )
        }
    }

    private fun catalogCodes(foods: List<Food>): Set<AvoidanceSubstanceCodeRef> {
        val codes = foods
            .flatMap { food -> food.avoidanceSubstances.map { it.substanceCode } }
            .map { AvoidanceSubstanceCode.valueOf(it.value) }
            .toSet()
        return avoidanceSubstanceRepository.findByCodes(codes)
            .map { AvoidanceSubstanceCodeRef(it.code.name) }
            .toSet()
    }
}
