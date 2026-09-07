package com.kbap.api.infra.place

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class GooglePlaceReadTimeoutTest : BehaviorSpec({

    given("Places 클라이언트 read timeout (KB-451 §3 — 전 경로 공통 2s)") {
        `when`("설정 상수를 확인하면") {
            then("read timeout 은 2초다 — nearby·search·주문 resolver 가 이 빈을 공유한다") {
                GooglePlaceSearchClient.READ_TIMEOUT_SECONDS shouldBe 2L
            }
        }
    }
})
