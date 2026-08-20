package com.kbap.common.domain.order.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class OrderTest : BehaviorSpec({

    given("주문 생성") {
        `when`("좌표와 주소를 함께 주면") {
            then("전부 보존된다") {
                val order = Order.place(
                    memberId = 1L,
                    imagePath = "scan/1/menu.jpg",
                    latitude = BigDecimal("37.5636000"),
                    longitude = BigDecimal("126.9834000"),
                    roadAddress = "서울 중구 소공로 51",
                )
                order.memberId shouldBe 1L
                order.imagePath shouldBe "scan/1/menu.jpg"
                order.roadAddress shouldBe "서울 중구 소공로 51"
            }
        }

        `when`("좌표 없이 주면") {
            then("위치 정보가 전부 비어 있다") {
                val order = Order.place(1L, "scan/1/menu.jpg", null, null, null)
                order.latitude.shouldBeNull()
                order.longitude.shouldBeNull()
                order.roadAddress.shouldBeNull()
            }
        }

        `when`("좌표를 한쪽만 주면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    Order.place(1L, "scan/1/menu.jpg", BigDecimal("37.5"), null, null)
                }
                shouldThrow<IllegalArgumentException> {
                    Order.place(1L, "scan/1/menu.jpg", null, BigDecimal("127.0"), null)
                }
            }
        }

        `when`("좌표가 범위를 벗어나면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    Order.place(1L, "scan/1/menu.jpg", BigDecimal("90.0000001"), BigDecimal("127.0"), null)
                }
                shouldThrow<IllegalArgumentException> {
                    Order.place(1L, "scan/1/menu.jpg", BigDecimal("37.5"), BigDecimal("-180.0000001"), null)
                }
            }
        }

        `when`("imagePath 가 비어 있으면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    Order.place(1L, " ", null, null, null)
                }
            }
        }
    }
})
