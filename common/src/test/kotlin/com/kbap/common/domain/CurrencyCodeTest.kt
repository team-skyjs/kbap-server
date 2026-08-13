package com.kbap.common.domain

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class CurrencyCodeTest : BehaviorSpec({

    given("취급 통화 목록") {
        `when`("전 통화를 훑으면") {
            then("모두 0 보다 큰 환율을 갖는다") {
                CurrencyCode.entries.forEach { currency ->
                    withClue(currency.name) { (currency.krwPerUnit > BigDecimal.ZERO) shouldBe true }
                }
            }

            then("라벨이 비어 있는 통화가 없다") {
                CurrencyCode.entries.forEach { currency ->
                    withClue(currency.name) { currency.label.isNotBlank() shouldBe true }
                }
            }

            then("기준 통화 KRW 의 환율은 1 이다") {
                CurrencyCode.KRW.krwPerUnit.compareTo(BigDecimal.ONE) shouldBe 0
            }
        }
    }

    given("1 원 미만 가치의 통화") {
        `when`("자릿수를 확인하면") {
            then("4자리로 보존돼 반올림 손실이 없다") {
                CurrencyCode.VND.krwPerUnit shouldBe BigDecimal("0.0544")
                CurrencyCode.IDR.krwPerUnit shouldBe BigDecimal("0.0805")
            }
        }
    }

    given("통화 코드 파싱") {
        `when`("정확히 일치하는 코드를 주면") {
            then("해당 통화를 돌려준다") {
                CurrencyCode.from("KRW") shouldBe CurrencyCode.KRW
                CurrencyCode.from("JPY") shouldBe CurrencyCode.JPY
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
