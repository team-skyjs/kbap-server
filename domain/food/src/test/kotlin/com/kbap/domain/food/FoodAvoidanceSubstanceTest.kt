package com.kbap.domain.food

import com.kbap.core.risk.RiskLevel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodAvoidanceSubstanceTest : BehaviorSpec({
    fun ref(code: String) = AvoidanceSubstanceCodeRef(code)

    given("FoodAvoidanceSubstance 생성 — inclusionProbability 경계") {
        `when`("inclusionProbability 가 1 이면") {
            then("정상 생성되고 값을 보존한다") {
                val substance = FoodAvoidanceSubstance(substanceCode = ref("EGG"), inclusionProbability = 1)

                substance.substanceCode shouldBe ref("EGG")
                substance.inclusionProbability shouldBe 1
            }
        }

        `when`("inclusionProbability 가 100 이면") {
            then("정상 생성되고 값을 보존한다") {
                FoodAvoidanceSubstance(substanceCode = ref("EGG"), inclusionProbability = 100)
                    .inclusionProbability shouldBe 100
            }
        }

        `when`("inclusionProbability 가 0 이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    FoodAvoidanceSubstance(substanceCode = ref("EGG"), inclusionProbability = 0)
                }
            }
        }

        `when`("inclusionProbability 가 101 이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    FoodAvoidanceSubstance(substanceCode = ref("EGG"), inclusionProbability = 101)
                }
            }
        }

        `when`("inclusionProbability 가 음수이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    FoodAvoidanceSubstance(substanceCode = ref("EGG"), inclusionProbability = -1)
                }
            }
        }
    }

    given("음식 기피 성분의 포함 확률로 위험도를 판정한다") {
        `when`("포함 확률이 CAUTION 임계값 미만이면") {
            then("확률 9 는 SAFE 다") {
                FoodAvoidanceSubstance(substanceCode = ref("SOY"), inclusionProbability = 9).riskLevel() shouldBe RiskLevel.SAFE
            }
        }
        `when`("포함 확률이 CAUTION 임계값 이상 DANGER 임계값 미만이면") {
            then("확률 10 은 CAUTION 이다") {
                FoodAvoidanceSubstance(substanceCode = ref("SOY"), inclusionProbability = 10).riskLevel() shouldBe RiskLevel.CAUTION
            }
            then("확률 59 는 CAUTION 이다") {
                FoodAvoidanceSubstance(substanceCode = ref("SOY"), inclusionProbability = 59).riskLevel() shouldBe RiskLevel.CAUTION
            }
        }
        `when`("포함 확률이 DANGER 임계값 이상이면") {
            then("확률 60 은 DANGER 다") {
                FoodAvoidanceSubstance(substanceCode = ref("SOY"), inclusionProbability = 60).riskLevel() shouldBe RiskLevel.DANGER
            }
            then("확률 100 은 DANGER 다") {
                FoodAvoidanceSubstance(substanceCode = ref("SOY"), inclusionProbability = 100).riskLevel() shouldBe RiskLevel.DANGER
            }
        }
    }
})
