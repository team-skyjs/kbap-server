package com.kbap.common.domain.food.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class FoodContentOutboxTest : BehaviorSpec({
    given("수집 요청 생성") {
        `when`("음식 id 와 표시명으로 만들면") {
            val outbox = FoodContentOutbox.pending(foodId = 7, displayName = "들깨 칼국수")

            then("발행 대기 상태로 시작한다") {
                outbox.foodId shouldBe 7
                outbox.displayName shouldBe "들깨 칼국수"
                outbox.outboxStatus shouldBe FoodContentOutboxStatus.PENDING
                outbox.attempts shouldBe 0
                outbox.sentAt.shouldBeNull()
            }
        }

        `when`("표시명이 비어 있으면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    FoodContentOutbox.pending(foodId = 7, displayName = " ")
                }
            }
        }
    }

    given("발행 결과 기록") {
        `when`("발행에 성공하면") {
            val outbox = FoodContentOutbox.pending(foodId = 7, displayName = "들깨 칼국수")

            outbox.markSent()

            then("발행 완료가 되고 발행 시각이 남는다") {
                outbox.outboxStatus shouldBe FoodContentOutboxStatus.SENT
                outbox.sentAt.shouldNotBeNull()
                outbox.attempts shouldBe 1
            }
        }

        `when`("발행에 실패하면") {
            val outbox = FoodContentOutbox.pending(foodId = 7, displayName = "들깨 칼국수")

            outbox.markFailed()

            then("다음 주기에 다시 집히도록 대기 상태를 유지한다") {
                outbox.outboxStatus shouldBe FoodContentOutboxStatus.PENDING
                outbox.attempts shouldBe 1
                outbox.sentAt.shouldBeNull()
            }
        }
    }
})
