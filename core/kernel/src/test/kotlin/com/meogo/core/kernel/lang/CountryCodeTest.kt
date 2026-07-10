package com.meogo.core.kernel.lang

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class CountryCodeTest : BehaviorSpec({

    given("CountryCode 카탈로그") {
        `when`("전체 상수를 조회하면") {
            then("ISO 3166-1 alpha-2 197개국을 보유한다") {
                CountryCode.entries shouldHaveSize 197
            }
        }

        `when`("상수명 형식을 확인하면") {
            then("모두 대문자 2자이며 중복이 없다") {
                val format = Regex("^[A-Z]{2}$")
                CountryCode.entries.forEach { it.name.matches(format) shouldBe true }
                CountryCode.entries.map { it.name }.toSet() shouldHaveSize 197
            }
        }

        `when`("대표 국가를 조회하면") {
            then("코드와 한국어 label 을 가진다") {
                CountryCode.KR.label shouldBe "대한민국"
                CountryCode.US.label shouldBe "미국"
                CountryCode.JP.label shouldBe "일본"
            }
        }

        `when`("모든 상수의 label 을 확인하면") {
            then("빈 값이 없다") {
                CountryCode.entries.forEach { it.label.isNotBlank() shouldBe true }
            }
        }
    }

    given("CountryCode.from — 코드 문자열 변환") {
        `when`("유효한 코드를 주면") {
            then("해당 상수를 반환한다") {
                CountryCode.from("KR") shouldBe CountryCode.KR
            }
        }

        `when`("null·빈 문자열·미지 코드를 주면") {
            then("null 을 반환한다") {
                CountryCode.from(null) shouldBe null
                CountryCode.from("") shouldBe null
                CountryCode.from("kr") shouldBe null
                CountryCode.from("ZZ") shouldBe null
            }
        }
    }
})
