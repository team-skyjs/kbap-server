package com.kbap.api.core

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class AppVersionTest : BehaviorSpec({
    given("AppVersion 파싱") {
        `when`("정상 semver 문자열이면") {
            then("각 파트를 숫자로 읽는다") {
                AppVersion.parseOrNull("1.1.0") shouldBe AppVersion(1, 1, 0)
                AppVersion.parseOrNull("10.2.33") shouldBe AppVersion(10, 2, 33)
            }
        }

        `when`("파트를 생략하면") {
            then("빠진 파트를 0 으로 채운다") {
                AppVersion.parseOrNull("1.1") shouldBe AppVersion(1, 1, 0)
                AppVersion.parseOrNull("2") shouldBe AppVersion(2, 0, 0)
            }
        }

        `when`("공백이 둘러싸면") {
            then("정리해 읽는다") {
                AppVersion.parseOrNull(" 1.1.0 ") shouldBe AppVersion(1, 1, 0)
            }
        }

        `when`("형식이 아니면") {
            then("null 을 반환한다") {
                listOf(null, "", "abc", "1.a.0", "1.1.0.0", "-1.0.0", "1..0").forEach {
                    AppVersion.parseOrNull(it) shouldBe null
                }
            }
        }
    }

    given("AppVersion 비교") {
        `when`("각 파트를 자릿수가 아니라 숫자로 비교하면") {
            then("1.10.0 이 1.9.0 보다 크다") {
                (AppVersion(1, 10, 0) > AppVersion(1, 9, 0)).shouldBeTrue()
                (AppVersion(2, 0, 0) > AppVersion(1, 99, 99)).shouldBeTrue()
                (AppVersion(1, 1, 0) >= AppVersion(1, 1, 0)).shouldBeTrue()
                (AppVersion(1, 0, 9) < AppVersion(1, 1, 0)).shouldBeTrue()
            }
        }
    }
})
