package com.meogo.application.client.food.usecase

import com.meogo.core.kernel.risk.RiskLevel
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class MockAvoidanceRiskMarkerTest : BehaviorSpec({
    val marker = MockAvoidanceRiskMarker()

    given("MockAvoidanceRiskMarker 기피 성분 위험 표시") {
        `when`("성분 코드 목록이 주어지면") {
            then("첫 성분만 CAUTION, 나머지는 SAFE 를 코드 키 맵으로 반환한다") {
                marker.mark(listOf("SOY", "WHEAT", "CLAM", "EGG")) shouldBe mapOf(
                    "SOY" to RiskLevel.CAUTION,
                    "WHEAT" to RiskLevel.SAFE,
                    "CLAM" to RiskLevel.SAFE,
                    "EGG" to RiskLevel.SAFE,
                )
            }
        }

        `when`("성분 코드가 한 개면") {
            then("그 성분에 CAUTION 만 부여한다") {
                marker.mark(listOf("SOY")) shouldBe mapOf("SOY" to RiskLevel.CAUTION)
            }
        }

        `when`("성분 코드 목록이 비어 있으면") {
            then("빈 맵을 반환한다") {
                marker.mark(emptyList()) shouldBe emptyMap()
            }
        }
    }
})
