package com.kbap.api.scan

import com.kbap.common.domain.ingredient.model.Avoidance
import com.kbap.common.domain.ingredient.model.IngredientCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class EstimatedRiskJudgeTest : BehaviorSpec({
    given("LLM 추정 판정(S-959)") {
        `when`("추정 코드가 없거나 회피 재료가 없으면") {
            then("SAFE 가 아니라 UNKNOWN 이다 — 빈 목록이 SAFE 가 되는 RiskLevel.aggregate 와 다르다") {
                EstimatedRiskJudge.judge(emptyList(), Avoidance(setOf(IngredientCode.PORK))) shouldBe EstimatedRisk.UNKNOWN
                EstimatedRiskJudge.judge(listOf("BEEF"), Avoidance.NONE) shouldBe EstimatedRisk.UNKNOWN
            }
        }
        `when`("겹치는 재료가 있으면(직접·회원 상위 코드의 하위·LLM 상위 코드의 하위)") {
            then("CAUTION 이다") {
                EstimatedRiskJudge.judge(listOf("BEEF"), Avoidance(setOf(IngredientCode.BEEF))) shouldBe EstimatedRisk.CAUTION
                EstimatedRiskJudge.judge(listOf("SALTED_SHRIMP"), Avoidance(setOf(IngredientCode.SHRIMP))) shouldBe EstimatedRisk.CAUTION
                EstimatedRiskJudge.judge(listOf("SHRIMP"), Avoidance(setOf(IngredientCode.SALTED_SHRIMP))) shouldBe EstimatedRisk.CAUTION
            }
        }
        `when`("모르는 코드 문자열이면") {
            then("무시하고 UNKNOWN 이다") {
                EstimatedRiskJudge.judge(listOf("NOT_A_CODE"), Avoidance(setOf(IngredientCode.BEEF))) shouldBe EstimatedRisk.UNKNOWN
            }
        }
        `when`("판정 결과 타입은") {
            then("CAUTION·UNKNOWN 두 값뿐이다") {
                EstimatedRisk.entries.map { it.name } shouldBe listOf("CAUTION", "UNKNOWN")
            }
        }
    }
})
