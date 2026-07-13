package com.meogo.domain.food

import com.meogo.core.lang.LocalizedText
import com.meogo.core.risk.RiskLevel
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodOverallRiskTest : BehaviorSpec({
    fun doenjangStew() = Food.reconstitute(
        id = 1,
        content = FoodContent(
            name = LocalizedText(korean = "된장찌개"),
            description = LocalizedText(korean = "구수한 된장찌개"),
        ),
        imageRef = "doenjang.png",
        spiciness = FoodSpiciness(3),
        avoidanceSubstances = listOf(
            FoodAvoidanceSubstance(substanceCode = AvoidanceSubstanceCodeRef("SOY"), inclusionProbability = 100),
            FoodAvoidanceSubstance(substanceCode = AvoidanceSubstanceCodeRef("WHEAT"), inclusionProbability = 80),
            FoodAvoidanceSubstance(substanceCode = AvoidanceSubstanceCodeRef("CLAM"), inclusionProbability = 50),
        ),
    )

    given("사용자 회피 성분과 음식 성분의 교집합으로 종합 위험도를 판정한다") {
        `when`("회피 성분이 확률 100 인 성분(SOY)과 겹치면") {
            then("DANGER 다") {
                doenjangStew().overallRisk(setOf(AvoidanceSubstanceCodeRef("SOY"))) shouldBe RiskLevel.DANGER
            }
        }
        `when`("회피 성분이 확률 50 인 성분(CLAM)과 겹치면") {
            then("CAUTION 이다") {
                doenjangStew().overallRisk(setOf(AvoidanceSubstanceCodeRef("CLAM"))) shouldBe RiskLevel.CAUTION
            }
        }
        `when`("회피 성분이 음식 성분과 하나도 겹치지 않으면") {
            then("교집합이 비어 SAFE 다") {
                doenjangStew().overallRisk(setOf(AvoidanceSubstanceCodeRef("MILK"))) shouldBe RiskLevel.SAFE
            }
        }
        `when`("회피 성분이 여러 성분(SOY·CLAM)과 겹치면") {
            then("겹친 성분들의 최악 위험도인 DANGER 다") {
                doenjangStew().overallRisk(setOf(AvoidanceSubstanceCodeRef("SOY"), AvoidanceSubstanceCodeRef("CLAM"))) shouldBe RiskLevel.DANGER
            }
        }
        `when`("회피 성분 목록이 비어 있으면") {
            then("교집합이 비어 SAFE 다") {
                doenjangStew().overallRisk(emptySet()) shouldBe RiskLevel.SAFE
            }
        }
    }

    given("음식에 기피 성분이 하나도 없다") {
        `when`("어떤 회피 성분으로 판정해도") {
            then("교집합이 비어 SAFE 다") {
                val plainRice = Food.reconstitute(
                    id = 2,
                    content = FoodContent(
                        name = LocalizedText(korean = "흰밥"),
                        description = LocalizedText(korean = "흰밥은 쌀로 지은 밥이다."),
                    ),
                    imageRef = null,
                    spiciness = FoodSpiciness(0),
                    avoidanceSubstances = emptyList(),
                )

                plainRice.overallRisk(setOf(AvoidanceSubstanceCodeRef("SOY"), AvoidanceSubstanceCodeRef("WHEAT"), AvoidanceSubstanceCodeRef("CLAM"))) shouldBe RiskLevel.SAFE
            }
        }
    }
})
