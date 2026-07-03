package com.meogo.core.food

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodContentTest : BehaviorSpec({
    val validName = "된장찌개"
    val validDescription = "구수한 한국식 된장찌개"

    fun content(
        koreanName: String = validName,
        description: String = validDescription,
    ) = FoodContent(koreanName = koreanName, description = description)

    given("FoodContent 생성 — 이름 제약") {
        `when`("이름이 빈 문자열이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { content(koreanName = "") }
            }
        }

        `when`("이름이 공백뿐이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { content(koreanName = "   ") }
            }
        }

        `when`("이름이 255자와 같으면") {
            then("정상 생성되고 값을 보존한다") {
                content(koreanName = "가".repeat(255)).koreanName shouldBe "가".repeat(255)
            }
        }

        `when`("이름이 256자를 초과하면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { content(koreanName = "가".repeat(256)) }
            }
        }
    }

    given("FoodContent 생성 — 설명 제약") {
        `when`("설명이 빈 문자열이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { content(description = "") }
            }
        }

        `when`("설명이 공백뿐이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { content(description = "   ") }
            }
        }

        `when`("설명이 255자와 같으면") {
            then("정상 생성되고 값을 보존한다") {
                content(description = "나".repeat(255)).description shouldBe "나".repeat(255)
            }
        }

        `when`("설명이 256자를 초과하면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { content(description = "나".repeat(256)) }
            }
        }
    }
})
