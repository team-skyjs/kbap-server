package com.meogo.core.scan

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class MenuItemMatchTest : BehaviorSpec({
    given("MenuItemMatch 매칭 결과") {
        `when`("Matched 를 음식 식별자로 만들면") {
            then("그 foodId 를 갖는다") {
                val matched = MenuItemMatch.Matched(foodId = 7L)
                matched.foodId shouldBe 7L
            }
        }

        `when`("Matched 의 foodId 가 양수가 아니면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { MenuItemMatch.Matched(foodId = 0L) }
            }
        }

        `when`("Pending 을 미완성 음식 식별자로 만들면") {
            then("그 foodId 를 갖는다") {
                MenuItemMatch.Pending(foodId = 55L).foodId shouldBe 55L
            }
        }

        `when`("Pending 의 foodId 가 양수가 아니면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { MenuItemMatch.Pending(foodId = 0L) }
            }
        }

        `when`("같은 foodId 라도 Matched 와 Pending 은") {
            then("서로 구별된다") {
                (MenuItemMatch.Matched(7L) == MenuItemMatch.Pending(7L)) shouldBe false
                MenuItemMatch.NotFood shouldBe MenuItemMatch.NotFood
            }
        }
    }
})
