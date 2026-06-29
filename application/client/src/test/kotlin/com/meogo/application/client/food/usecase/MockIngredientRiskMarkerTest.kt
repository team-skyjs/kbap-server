package com.meogo.application.client.food.usecase

import com.meogo.core.kernel.risk.RiskLevel
import com.meogo.core.food.Ingredient
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class MockIngredientRiskMarkerTest : BehaviorSpec({
    val marker = MockIngredientRiskMarker()

    fun ingredient(id: Long, koreanName: String) =
        Ingredient.reconstitute(id = id, koreanName = koreanName, iconRef = null)

    given("MockIngredientRiskMarker 재료 위험 표시") {
        `when`("재료 목록이 주어지면") {
            then("첫 재료만 CAUTION, 나머지는 SAFE 를 id 키 맵으로 반환한다") {
                val ingredients = listOf(
                    ingredient(1, "바지락 조개"),
                    ingredient(2, "된장"),
                    ingredient(3, "두부"),
                    ingredient(4, "소고기"),
                )

                marker.mark(ingredients) shouldBe mapOf(
                    1L to RiskLevel.CAUTION,
                    2L to RiskLevel.SAFE,
                    3L to RiskLevel.SAFE,
                    4L to RiskLevel.SAFE,
                )
            }
        }

        `when`("재료가 한 개면") {
            then("그 재료에 CAUTION 만 부여한다") {
                marker.mark(listOf(ingredient(1, "된장"))) shouldBe mapOf(1L to RiskLevel.CAUTION)
            }
        }

        `when`("재료 목록이 비어 있으면") {
            then("빈 맵을 반환한다") {
                marker.mark(emptyList()) shouldBe emptyMap()
            }
        }
    }
})
