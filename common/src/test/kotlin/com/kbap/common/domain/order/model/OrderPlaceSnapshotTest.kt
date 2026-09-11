package com.kbap.common.domain.order.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class OrderPlaceSnapshotTest : BehaviorSpec({

    given("OrderPlaceSnapshot 생성") {
        `when`("정상 값을 주면") {
            then("스냅샷이 보존되고 source 는 GOOGLE_PLACE 다") {
                val snap = OrderPlaceSnapshot(externalId = "place-1", name = "백년옥", address = "서울", language = "en")
                snap.source shouldBe OrderPlaceSource.GOOGLE_PLACE
                snap.externalId shouldBe "place-1"
                snap.name shouldBe "백년옥"
                snap.address shouldBe "서울"
                snap.language shouldBe "en"
            }
        }

        `when`("name 이 최대 길이를 넘으면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    OrderPlaceSnapshot(externalId = "place-1", name = "가".repeat(101), language = "ko")
                }
            }
        }

        `when`("address 가 최대 길이를 넘으면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    OrderPlaceSnapshot(externalId = "place-1", name = "백년옥", address = "나".repeat(201), language = "ko")
                }
            }
        }
    }
})
