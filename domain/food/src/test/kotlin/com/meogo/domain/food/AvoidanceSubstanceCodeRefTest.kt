package com.meogo.domain.food

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class AvoidanceSubstanceCodeRefTest : BehaviorSpec({
    given("AvoidanceSubstanceCodeRef 생성") {
        `when`("대문자·숫자·underscore 형식이면") {
            then("정상 생성되고 값을 보존한다") {
                AvoidanceSubstanceCodeRef("SOY").value shouldBe "SOY"
                AvoidanceSubstanceCodeRef("GOAT_MILK").value shouldBe "GOAT_MILK"
            }
        }

        `when`("빈 문자열이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { AvoidanceSubstanceCodeRef("") }
            }
        }

        `when`("앞뒤 공백이 있으면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { AvoidanceSubstanceCodeRef(" SOY") }
                shouldThrow<IllegalArgumentException> { AvoidanceSubstanceCodeRef("SOY ") }
            }
        }

        `when`("소문자가 섞여 있으면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { AvoidanceSubstanceCodeRef("Soy") }
            }
        }

        `when`("허용되지 않는 특수문자가 있으면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { AvoidanceSubstanceCodeRef("SOY-MILK") }
            }
        }
    }
})
