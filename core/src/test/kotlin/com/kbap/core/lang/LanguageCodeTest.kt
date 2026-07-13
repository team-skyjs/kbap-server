package com.kbap.core.lang

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

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

        `when`("미지정(null)·빈·공백 문자열이 주어지면") {
            then("ko 로 기본 처리한다") {
                LanguageCode.from(null) shouldBe LanguageCode.KO
                LanguageCode.from("") shouldBe LanguageCode.KO
                LanguageCode.from("   ") shouldBe LanguageCode.KO
            }
        }

        `when`("지원 목록과 정확히 일치하지 않는 코드가 주어지면") {
            then("LanguageException 을 던진다") {
                shouldThrow<LanguageException> { LanguageCode.from("xx") }
                shouldThrow<LanguageException> { LanguageCode.from("EN") }
                shouldThrow<LanguageException> { LanguageCode.from("ko-KR") }
                shouldThrow<LanguageException> { LanguageCode.from(" fr ") }
            }
        }

        `when`("미지원 코드로 예외가 발생하면") {
            then("메시지에 지원 언어 코드 10종이 모두 포함된다") {
                val message = shouldThrow<LanguageException> { LanguageCode.from("fr") }.message ?: ""
                listOf("ko", "zh-Hans", "en", "ja", "zh-Hant", "vi", "id", "th", "ru", "es")
                    .forEach { code -> message shouldContain code }
            }
        }
    }
})
