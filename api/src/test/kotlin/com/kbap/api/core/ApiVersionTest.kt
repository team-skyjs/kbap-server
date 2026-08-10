package com.kbap.api.core

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class ApiVersionTest : BehaviorSpec({
    given("ApiVersion 파싱 — yyyy.mm.sprint차수") {
        `when`("정상 표기면") {
            then("세 파트를 숫자로 읽는다 — zero-pad 유무 무관") {
                ApiVersion.parseOrNull("2026.08.07") shouldBe ApiVersion(2026, 8, 7)
                ApiVersion.parseOrNull("2026.8.7") shouldBe ApiVersion(2026, 8, 7)
                ApiVersion.parseOrNull(" 2026.12.10 ") shouldBe ApiVersion(2026, 12, 10)
            }
        }

        `when`("형식이 아니면") {
            then("null 을 반환한다") {
                listOf(null, "", "beta", "2026.08", "2026.08.07.01", "2026.-1.07", "2026..07", "v2026.08.07")
                    .forEach { ApiVersion.parseOrNull(it) shouldBe null }
            }
        }
    }

    given("ApiVersion 비교") {
        `when`("각 파트를 자릿수가 아니라 숫자로 비교하면") {
            then("연도 → 월 → 스프린트 순으로 우선한다") {
                (ApiVersion(2026, 8, 7) >= ApiVersion(2026, 8, 7)).shouldBeTrue()
                (ApiVersion(2026, 8, 10) > ApiVersion(2026, 8, 9)).shouldBeTrue()
                (ApiVersion(2026, 9, 1) > ApiVersion(2026, 8, 99)).shouldBeTrue()
                (ApiVersion(2027, 1, 1) > ApiVersion(2026, 12, 99)).shouldBeTrue()
                (ApiVersion(2026, 8, 6) < ApiVersion(2026, 8, 7)).shouldBeTrue()
            }
        }
    }
})
