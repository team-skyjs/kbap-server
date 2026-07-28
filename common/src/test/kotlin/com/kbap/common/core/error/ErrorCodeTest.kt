package com.kbap.common.core.error

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ErrorCodeTest : BehaviorSpec({

    given("인증 토큰 에러 메시지") {
        `when`("액세스 토큰이 유효하지 않으면") {
            then("재로그인을 안내한다") {
                ErrorCode.INVALID_ACCESS_TOKEN.message shouldBe "유효하지 않은 액세스 토큰입니다. 다시 로그인해 주세요"
            }
        }

        `when`("액세스 토큰이 만료되면") {
            then("토큰 갱신을 안내한다") {
                ErrorCode.EXPIRED_ACCESS_TOKEN.message shouldBe "만료된 액세스 토큰입니다. 토큰을 갱신해 주세요"
            }
        }

        `when`("리프레시 토큰이 유효하지 않으면") {
            then("재로그인을 안내한다") {
                ErrorCode.INVALID_REFRESH_TOKEN.message shouldBe "유효하지 않은 리프레시 토큰입니다. 다시 로그인해 주세요"
            }
        }

        `when`("리프레시 토큰이 만료되면") {
            then("재로그인을 안내한다") {
                ErrorCode.EXPIRED_REFRESH_TOKEN.message shouldBe "만료된 리프레시 토큰입니다. 다시 로그인해 주세요"
            }
        }

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
