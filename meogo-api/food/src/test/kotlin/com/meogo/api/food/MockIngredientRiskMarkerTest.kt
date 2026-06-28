package com.meogo.api.food

import com.meogo.api.core.risk.RiskLevel
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class MockIngredientRiskMarkerTest : BehaviorSpec({
    val marker = MockIngredientRiskMarker()

    fun ingredient(koreanName: String) =
        Ingredient(koreanName = koreanName, iconRef = null)

    given("MockIngredientRiskMarker 재료 위험 표시") {
        `when`("재료 목록이 주어지면") {
            then("첫 재료는 CAUTION, 나머지는 모두 SAFE 를 평행 리스트로 반환한다") {
                val ingredients = listOf(
                    ingredient("바지락 조개"),
                    ingredient("된장"),
                    ingredient("두부"),
                    ingredient("소고기"),
                )

                marker.mark(ingredients) shouldBe listOf(
                    RiskLevel.CAUTION,
                    RiskLevel.SAFE,
                    RiskLevel.SAFE,
                    RiskLevel.SAFE,
                )
            }
        }

        `when`("재료가 한 개면") {
            then("그 재료에 CAUTION 만 부여한다") {
                marker.mark(listOf(ingredient("된장"))) shouldBe listOf(RiskLevel.CAUTION)
            }
        }

        `when`("재료 목록이 비어 있으면") {
            then("빈 리스트를 반환한다") {
                marker.mark(emptyList()) shouldBe emptyList()
            }
        }
    }
})
