package com.meogo.domain.food

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodSpicinessTest : BehaviorSpec({
    given("FoodSpiciness 생성 — 0..10 범위") {
        `when`("0 이면") {
            then("정상 생성되고 값을 보존한다") {
                FoodSpiciness(0).value shouldBe 0
            }
        }

        `when`("10 이면") {
            then("정상 생성되고 값을 보존한다") {
                FoodSpiciness(10).value shouldBe 10
            }
        }

        `when`("-1 이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { FoodSpiciness(-1) }
            }
        }

        `when`("11 이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { FoodSpiciness(11) }
            }
        }
    }
})
