package com.meogo.application.client.food.usecase

import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.avoidance.AvoidanceSubstanceRepository
import com.meogo.core.food.AvoidanceSubstanceCodeRef
import com.meogo.core.food.Food
import com.meogo.core.kernel.risk.RiskLevel
import org.springframework.stereotype.Component

@Component
class FoodRiskEvaluator(
    private val avoidedSubstanceProvider: AvoidedSubstanceProvider,
    private val avoidanceSubstanceRepository: AvoidanceSubstanceRepository,
) {
    fun risksOf(foods: List<Food>): Map<Long, RiskLevel> {
        if (foods.isEmpty()) return emptyMap()

        val avoidedCodes = avoidedSubstanceProvider.avoidedCodes()
            .map { AvoidanceSubstanceCodeRef(it.name) }
            .toSet()
        val catalogCodes = catalogCodes(foods)

        return foods.associate { food ->
            val resolvableCodes = food.avoidanceSubstances
                .map { it.substanceCode }
                .filter { it in catalogCodes }
                .toSet()
            requireNotNull(food.id) { "위험도 산출 대상 food 에 id 가 없습니다" } to
                food.overallRisk(avoidedCodes intersect resolvableCodes)
        }
    }

    private fun catalogCodes(foods: List<Food>): Set<AvoidanceSubstanceCodeRef> {
        val codes = foods
            .flatMap { food -> food.avoidanceSubstances.map { it.substanceCode } }
            .map { AvoidanceSubstanceCode.valueOf(it.value) }
            .toSet()
        if (codes.isEmpty()) return emptySet()
        return avoidanceSubstanceRepository.findByCodes(codes)
            .map { AvoidanceSubstanceCodeRef(it.code.name) }
            .toSet()
    }
}
