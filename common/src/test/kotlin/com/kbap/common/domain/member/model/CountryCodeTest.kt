package com.kbap.common.domain.member.model

import com.kbap.common.domain.CurrencyCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class CountryCodeTest : BehaviorSpec({

    given("지원 국가 목록") {
        `when`("개수를 세면") {
            then("197개다") {
                CountryCode.entries.size shouldBe 197
            }
        }

        `when`("라벨을 훑으면") {
            then("비어 있는 국가가 없다") {
                CountryCode.entries.forEach { it.label.isNotBlank() shouldBe true }
            }
        }
    }

    given("취급 통화를 쓰는 국가") {
        `when`("통화를 조회하면") {
            then("그 국가의 실제 통화가 나온다") {
                CountryCode.KR.currency shouldBe CurrencyCode.KRW
                CountryCode.JP.currency shouldBe CurrencyCode.JPY
                CountryCode.IS.currency shouldBe CurrencyCode.ISK
                CountryCode.RO.currency shouldBe CurrencyCode.RON
            }
        }

        `when`("유로존 국가를 조회하면") {
            then("모두 같은 EUR 를 가리킨다") {
                CountryCode.FR.currency shouldBe CurrencyCode.EUR
                CountryCode.DE.currency shouldBe CurrencyCode.EUR
                CountryCode.IT.currency shouldBe CurrencyCode.EUR
            }
        }
    }

    given("취급 통화 밖 통화를 쓰는 국가") {
        `when`("통화를 조회하면") {
            then("USD 로 대체된다") {
                CountryCode.NG.currency shouldBe CurrencyCode.USD
                CountryCode.AO.currency shouldBe CurrencyCode.USD
                CountryCode.AR.currency shouldBe CurrencyCode.USD
            }
        }

        `when`("제공처 미지원으로 통화가 폐기된 국가를 조회하면") {
            then("USD 로 대체된다") {
                CountryCode.VN.currency shouldBe CurrencyCode.USD
                CountryCode.TW.currency shouldBe CurrencyCode.USD
                CountryCode.SA.currency shouldBe CurrencyCode.USD
                CountryCode.AE.currency shouldBe CurrencyCode.USD
            }
        }

        `when`("전 국가의 통화를 훑으면") {
            then("모두 취급 통화 30종 안이다") {
                CountryCode.entries.forEach { CurrencyCode.from(it.currency.name) shouldBe it.currency }
            }
        }
    }

    given("국가 코드 파싱") {
        `when`("정확히 일치하는 코드를 주면") {
            then("해당 국가를 돌려준다") {
                CountryCode.from("KR") shouldBe CountryCode.KR
            }
        }

        `when`("대소문자가 다르거나 없는 코드면") {
            then("null 을 돌려준다") {
                CountryCode.from("kr").shouldBeNull()
                CountryCode.from("ZZ").shouldBeNull()
                CountryCode.from(null).shouldBeNull()
            }
        }
    }
})
