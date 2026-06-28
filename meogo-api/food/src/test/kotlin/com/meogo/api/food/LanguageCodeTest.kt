package com.meogo.api.food

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class LanguageCodeTest : BehaviorSpec({
    given("LanguageCode.from 코드 해석") {
        `when`("9개 대상 언어 코드가 각각 주어지면") {
            then("해당 LanguageCode 로 해석된다") {
                val expected = mapOf(
                    "zh-Hans" to LanguageCode.ZH_HANS,
                    "en" to LanguageCode.EN,
                    "ja" to LanguageCode.JA,
                    "zh-Hant" to LanguageCode.ZH_HANT,
                    "vi" to LanguageCode.VI,
                    "id" to LanguageCode.ID,
                    "th" to LanguageCode.TH,
                    "ru" to LanguageCode.RU,
                    "es" to LanguageCode.ES,
                )
                expected.size shouldBe 9
                expected.forEach { (code, lang) -> LanguageCode.from(code) shouldBe lang }
            }
        }

        `when`("모든 LanguageCode 의 code 로 from 을 호출하면") {
            then("자기 자신으로 라운드트립한다(코드 오타 방지)") {
                LanguageCode.entries.forEach { lang ->
                    LanguageCode.from(lang.code) shouldBe lang
                }
            }
        }

        `when`("ko 가 주어지면") {
            then("KO 로 해석한다") {
                LanguageCode.from("ko") shouldBe LanguageCode.KO
            }
        }

        `when`("미지원 코드·blank·null 이 주어지면") {
            then("ko 로 폴백한다") {
                LanguageCode.from("xx") shouldBe LanguageCode.KO
                LanguageCode.from("EN") shouldBe LanguageCode.KO
                LanguageCode.from("") shouldBe LanguageCode.KO
                LanguageCode.from("   ") shouldBe LanguageCode.KO
                LanguageCode.from(null) shouldBe LanguageCode.KO
            }
        }
    }
})
