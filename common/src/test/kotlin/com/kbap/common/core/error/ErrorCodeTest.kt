package com.kbap.common.core.error

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ErrorCodeTest : BehaviorSpec({

    given("인증 토큰 에러 계약") {
        `when`("code·status 를 확인하면") {
            then("기존 식별자와 401 이 유지된다") {
                listOf(
                    ErrorCode.INVALID_ACCESS_TOKEN to "AUTH-003",
                    ErrorCode.EXPIRED_ACCESS_TOKEN to "AUTH-004",
                    ErrorCode.INVALID_REFRESH_TOKEN to "AUTH-005",
                    ErrorCode.EXPIRED_REFRESH_TOKEN to "AUTH-006",
                ).forEach { (errorCode, code) ->
                    errorCode.code shouldBe code
                    errorCode.status shouldBe 401
                }
            }
        }
    }
})
