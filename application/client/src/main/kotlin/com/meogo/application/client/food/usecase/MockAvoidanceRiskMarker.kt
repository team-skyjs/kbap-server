package com.meogo.application.client.food.usecase

import com.meogo.core.kernel.risk.RiskLevel
import org.springframework.stereotype.Component

@Component
class MockAvoidanceRiskMarker {
    fun mark(codes: List<String>): Map<String, RiskLevel> =
        codes.mapIndexed { index, code ->
            code to if (index == 0) RiskLevel.CAUTION else RiskLevel.SAFE
        }.toMap()
}
