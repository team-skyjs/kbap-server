package com.meogo.core.food

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
})
