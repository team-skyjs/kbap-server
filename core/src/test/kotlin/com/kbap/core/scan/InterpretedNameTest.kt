package com.kbap.core.scan

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class InterpretedNameTest : BehaviorSpec({
    given("InterpretedName 해석 결과") {
        `when`("StandardName 을 표준 한국어 이름으로 만들면") {
            then("그 이름을 갖는다") {
                InterpretedName.StandardName("김치찌개").korean shouldBe "김치찌개"
            }
        }

        `when`("StandardName 의 이름이 blank 이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { InterpretedName.StandardName(" ") }
            }
        }

        `when`("NotFood 는") {
            then("단일 인스턴스이고 StandardName 과 구별된다") {
                InterpretedName.NotFood shouldBe InterpretedName.NotFood
                (InterpretedName.NotFood == InterpretedName.StandardName("김치찌개")) shouldBe false
            }
        }
    }
})
