package com.meogo.api.application.scan

import com.meogo.api.core.risk.RiskLevel
import com.meogo.api.scan.MenuItemAssessment
import org.springframework.stereotype.Component

@Component
class MockCyclingRiskAssessor : MenuItemRiskAssessor {
    override fun assess(index: Int, rawMenuName: String): MenuItemAssessment {
        val level = LEVELS[index % LEVELS.size]
        return MenuItemAssessment(riskLevel = level, reason = REASONS.getValue(level))
    }

    companion object {
        private val LEVELS = listOf(RiskLevel.SAFE, RiskLevel.CAUTION, RiskLevel.DANGER, RiskLevel.UNKNOWN)

        private val REASONS = mapOf(
            RiskLevel.SAFE to "mock: 안전으로 판정된 항목",
            RiskLevel.CAUTION to "mock: 주의 항목 — 매장 확인 필요",
            RiskLevel.DANGER to "mock: 위험 항목",
            RiskLevel.UNKNOWN to "mock: 판정 대상 식별 불가",
        )
    }
}
