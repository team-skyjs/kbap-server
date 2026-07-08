package com.meogo.core.scan

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class PendingMenuTest : BehaviorSpec({
    given("PendingMenu 대기열 항목") {
        `when`("표준명으로 새로 만들면") {
            then("그 이름을 갖고 상태는 PENDING 이다") {
                val entry = PendingMenu.of("우주라면")
                entry.name shouldBe "우주라면"
                entry.status shouldBe PendingMenuStatus.PENDING
            }
        }

        `when`("표준명이 blank 이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { PendingMenu.of(" ") }
            }
        }
    }
})
