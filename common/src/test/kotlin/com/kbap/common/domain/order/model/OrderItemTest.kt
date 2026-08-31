package com.kbap.common.domain.order.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class OrderItemTest : BehaviorSpec({

    given("주문 항목 생성") {
        `when`("이름·수량·가격을 주면") {
            then("스냅샷 그대로 보존된다") {
                val item = OrderItem.place(orderId = 10L, foodId = 7L, menuName = "순두부찌개", quantity = 2, price = 9000)
                item.orderId shouldBe 10L
                item.foodId shouldBe 7L
                item.menuName shouldBe "순두부찌개"
                item.quantity shouldBe 2
                item.price shouldBe 9000
            }
        }

        `when`("가격 없이 주면") {
            then("가격만 비어 있는 항목이 된다") {
                OrderItem.place(10L, 7L, "반찬", 2, null).price shouldBe null
            }
        }

        `when`("수량이 0 이하면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { OrderItem.place(10L, 7L, "순두부찌개", 0, null) }
            }
        }

        `when`("메뉴명이 비어 있거나 100자를 넘으면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { OrderItem.place(10L, 7L, " ", 1, null) }
                shouldThrow<IllegalArgumentException> { OrderItem.place(10L, 7L, "가".repeat(101), 1, null) }
            }
        }

        `when`("가격이 음수면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { OrderItem.place(10L, 7L, "순두부찌개", 1, -1) }
            }
        }
    }
})
