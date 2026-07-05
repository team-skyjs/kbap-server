package com.meogo.application.client.food.usecase

import com.meogo.core.avoidance.AvoidanceSubstanceCode
import org.springframework.stereotype.Component

@Component
class MockAvoidedSubstanceProvider : AvoidedSubstanceProvider {
    override fun avoidedCodes(): Set<AvoidanceSubstanceCode> = MOCK

    companion object {
        val MOCK: Set<AvoidanceSubstanceCode> = setOf(
            AvoidanceSubstanceCode.SOY,
            AvoidanceSubstanceCode.MILK,
            AvoidanceSubstanceCode.PEANUT,
            AvoidanceSubstanceCode.SHRIMP,
            AvoidanceSubstanceCode.EGG,
        )
    }
}
