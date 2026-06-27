package com.meogo.application.scan

import com.meogo.core.risk.RiskLevel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class MockCyclingRiskAssessorTest : StringSpec({

    val assessor = MockCyclingRiskAssessor()

    "index % 4 로 SAFE/CAUTION/DANGER/UNKNOWN 을 순환 부여한다" {
        assessor.assess(0, "메뉴").riskLevel shouldBe RiskLevel.SAFE
        assessor.assess(1, "메뉴").riskLevel shouldBe RiskLevel.CAUTION
        assessor.assess(2, "메뉴").riskLevel shouldBe RiskLevel.DANGER
        assessor.assess(3, "메뉴").riskLevel shouldBe RiskLevel.UNKNOWN
    }

    "5번째(index 4)부터 SAFE 로 재순환한다" {
        assessor.assess(4, "메뉴").riskLevel shouldBe RiskLevel.SAFE
        assessor.assess(5, "메뉴").riskLevel shouldBe RiskLevel.CAUTION
    }

    "각 단계는 mock reason 문구를 가진다" {
        assessor.assess(0, "메뉴").reason shouldBe "mock: 안전으로 판정된 항목"
        assessor.assess(2, "메뉴").reason shouldBe "mock: 위험 항목"
    }
})
