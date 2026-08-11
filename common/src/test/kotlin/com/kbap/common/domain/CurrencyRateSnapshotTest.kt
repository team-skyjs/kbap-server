package com.kbap.common.domain

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.math.RoundingMode

class CurrencyRateSnapshotTest : BehaviorSpec({

    val price = BigDecimal("9000")

    fun convert(currency: CurrencyCode, scale: Int): BigDecimal =
        price.divide(currency.krwPerUnit, scale, RoundingMode.HALF_UP)

    given("9,000원 메뉴") {
        `when`("소수점 있는 통화로 환산하면") {
            then("고정 스냅샷 기준 금액이 나온다") {
                convert(CurrencyCode.USD, 2) shouldBe BigDecimal("6.36")
                convert(CurrencyCode.EUR, 2) shouldBe BigDecimal("5.51")
                convert(CurrencyCode.SGD, 2) shouldBe BigDecimal("8.14")
            }
        }

        `when`("소수점 없는 통화로 환산하면") {
            then("고정 스냅샷 기준 금액이 나온다") {
                convert(CurrencyCode.JPY, 0) shouldBe BigDecimal("1012")
                convert(CurrencyCode.IDR, 0) shouldBe BigDecimal("111801")
            }
        }

        `when`("1 원 미만 가치의 VND 로 환산하면") {
            then("165,441 동이다 — 환율을 2자리로 줄이면 8% 어긋나므로 회귀 감지 지점이다") {
                convert(CurrencyCode.VND, 0) shouldBe BigDecimal("165441")
            }
        }

        `when`("기준 통화 KRW 로 환산하면") {
            then("원금 그대로다") {
                convert(CurrencyCode.KRW, 0) shouldBe BigDecimal("9000")
            }
        }
    }
})
