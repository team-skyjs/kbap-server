package com.meogo.core.scan

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class MenuItemMatchTest : BehaviorSpec({
    given("MenuItemMatch 매칭 결과") {
        `when`("Matched 를 음식 식별자로 만들면") {
            then("그 foodId 를 갖는다") {
                MenuItemMatch.Matched(foodId = 7L).foodId shouldBe 7L
            }
        }

        `when`("Matched 의 foodId 가 양수가 아니면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { MenuItemMatch.Matched(foodId = 0L) }
            }
        }

        `when`("Unmatched 는 미완성 음식 식별자를 가질 수도, 없을 수도 있다") {
            then("조사 대기 음식이면 foodId 를, 판정 불가면 null 을 갖는다") {
                MenuItemMatch.Unmatched(foodId = 55L).foodId shouldBe 55L
                MenuItemMatch.Unmatched().foodId shouldBe null
            }
        }

        `when`("같은 foodId 라도") {
            then("Matched 와 Unmatched 는 서로 구별된다") {
                (MenuItemMatch.Matched(7L) == MenuItemMatch.Unmatched(7L)) shouldBe false
            }
        }
    }
})
