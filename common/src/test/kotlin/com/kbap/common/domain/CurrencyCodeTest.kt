package com.kbap.common.domain

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class CurrencyCodeTest : BehaviorSpec({

    given("취급 통화 목록") {
        `when`("전 통화를 나열하면") {
            then("환율 제공처 지원 통화 전부 + 원화 = 30종이다") {
                CurrencyCode.entries.map { it.name } shouldContainExactly listOf(
                    "AUD", "BRL", "CAD", "CHF", "CNY", "CZK", "DKK", "EUR", "GBP", "HKD",
                    "HUF", "IDR", "ILS", "INR", "ISK", "JPY", "KRW", "MXN", "MYR", "NOK",
                    "NZD", "PHP", "PLN", "RON", "SEK", "SGD", "THB", "TRY", "USD", "ZAR",
                )
            }

            then("라벨이 비어 있는 통화가 없다") {
                CurrencyCode.entries.forEach { it.label.isNotBlank() shouldBe true }
            }
        }
    }

    given("통화 코드 파싱") {
        `when`("정확히 일치하는 코드를 주면") {
            then("해당 통화를 돌려준다") {
                CurrencyCode.from("KRW") shouldBe CurrencyCode.KRW
                CurrencyCode.from("JPY") shouldBe CurrencyCode.JPY
                CurrencyCode.from("ISK") shouldBe CurrencyCode.ISK
                CurrencyCode.from("RON") shouldBe CurrencyCode.RON
            }
        }

        `when`("제공처 미지원으로 폐기된 코드를 주면") {
            then("null 을 돌려준다") {
                listOf(
                    "AED", "BDT", "BHD", "BND", "EGP", "FJD", "JOD", "KHR", "KWD",
                    "KZT", "MNT", "NPR", "PKR", "QAR", "RUB", "SAR", "TWD", "VND",
                ).forEach { CurrencyCode.from(it).shouldBeNull() }
            }
        }

        `when`("대소문자·앞뒤 공백이 다르면") {
            then("정규화하지 않고 null 을 돌려준다") {
                CurrencyCode.from("krw").shouldBeNull()
                CurrencyCode.from(" KRW ").shouldBeNull()
            }
        }

        `when`("지원 목록 밖이거나 null 이면") {
            then("null 을 돌려준다") {
                CurrencyCode.from("XAU").shouldBeNull()
                CurrencyCode.from(null).shouldBeNull()
            }
        }
    }
})
