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

        `when`("Pending 과 NotFood 는") {
            then("각각 단일 인스턴스이고 서로 구별된다") {
                MenuItemMatch.Pending shouldBe MenuItemMatch.Pending
                MenuItemMatch.NotFood shouldBe MenuItemMatch.NotFood
                (MenuItemMatch.Pending == MenuItemMatch.NotFood) shouldBe false
            }
        }
    }
})
