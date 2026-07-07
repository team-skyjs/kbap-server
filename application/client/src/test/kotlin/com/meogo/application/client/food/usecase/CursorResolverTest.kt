package com.meogo.application.client.food.usecase

import com.meogo.core.food.FoodErrorCode
import com.meogo.core.food.FoodException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class CursorResolverTest : BehaviorSpec({
    given("CursorResolver 커서 해석") {
        `when`("커서가 null 이면") {
            then("null 을 반환한다") {
                resolveCursor(null) shouldBe null
            }
        }

        `when`("커서가 빈 문자열이면") {
            then("첫 페이지로 보고 null 을 반환한다") {
                resolveCursor("") shouldBe null
            }
        }

        `when`("커서가 공백만 있으면") {
            then("첫 페이지로 보고 null 을 반환한다") {
                resolveCursor("   ") shouldBe null
            }
        }

        `when`("커서가 유효한 양수 문자열이면") {
            then("해당 Long 값을 반환한다") {
                resolveCursor("100") shouldBe 100L
            }
        }

        `when`("커서가 0 이면") {
            then("경계값 0L 을 그대로 반환한다") {
                resolveCursor("0") shouldBe 0L
            }
        }

        `when`("커서가 숫자가 아니면") {
            then("INVALID_CURSOR FoodException 을 던진다") {
                shouldThrow<FoodException> {
                    resolveCursor("abc")
                }.errorCode shouldBe FoodErrorCode.INVALID_CURSOR
            }
        }

        `when`("커서가 음수이면") {
            then("INVALID_CURSOR FoodException 을 던진다") {
                shouldThrow<FoodException> {
                    resolveCursor("-1")
                }.errorCode shouldBe FoodErrorCode.INVALID_CURSOR
            }
        }

        `when`("커서가 공백을 포함한 숫자이면") {
            then("파싱에 실패해 INVALID_CURSOR FoodException 을 던진다") {
                shouldThrow<FoodException> {
                    resolveCursor(" 100 ")
                }.errorCode shouldBe FoodErrorCode.INVALID_CURSOR
            }
        }
    }
})
