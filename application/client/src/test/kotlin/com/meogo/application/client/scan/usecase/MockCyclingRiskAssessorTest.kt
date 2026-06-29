package com.meogo.application.client.scan.usecase

import com.meogo.core.kernel.risk.RiskLevel
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class MockCyclingRiskAssessorTest : BehaviorSpec({
    val assessor = MockCyclingRiskAssessor()

    given("MockCyclingRiskAssessor 판정") {
        `when`("항목 index 가 0..3 으로 주어지면") {
            then("index % 4 로 SAFE/CAUTION/DANGER/UNKNOWN 을 순환 부여한다") {
                assessor.assess(0, "메뉴").riskLevel shouldBe RiskLevel.SAFE
                assessor.assess(1, "메뉴").riskLevel shouldBe RiskLevel.CAUTION
                assessor.assess(2, "메뉴").riskLevel shouldBe RiskLevel.DANGER
                assessor.assess(3, "메뉴").riskLevel shouldBe RiskLevel.UNKNOWN
            }
        }

        `when`("항목 index 가 4 이상이면") {
            then("index % 4 기준으로 SAFE 부터 재순환한다") {
                assessor.assess(4, "메뉴").riskLevel shouldBe RiskLevel.SAFE
                assessor.assess(5, "메뉴").riskLevel shouldBe RiskLevel.CAUTION
            }
        }

        `when`("판정이 부여되면") {
            then("각 단계별 mock reason 문구를 가진다") {
                assessor.assess(0, "메뉴").reason shouldBe "mock: 안전으로 판정된 항목"
                assessor.assess(2, "메뉴").reason shouldBe "mock: 위험 항목"
            }
        }
    }
})
