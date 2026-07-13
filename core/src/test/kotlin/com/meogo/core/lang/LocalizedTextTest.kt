package com.meogo.core.lang

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class LocalizedTextTest : BehaviorSpec({
    given("LocalizedText 언어 해석 resolve") {
        `when`("대상 언어가 KO 이면") {
            then("번역이 있어도 korean 원문을 반환한다") {
                LocalizedText(
                    korean = "김치찌개",
                    translations = mapOf(LanguageCode.EN to "Kimchi Stew"),
                ).resolve(LanguageCode.KO) shouldBe "김치찌개"
            }
        }

        `when`("대상 언어의 번역이 존재하면") {
            then("해당 번역값을 반환한다") {
                LocalizedText(
                    korean = "김치찌개",
                    translations = mapOf(LanguageCode.EN to "Kimchi Stew"),
                ).resolve(LanguageCode.EN) shouldBe "Kimchi Stew"
            }
        }

        `when`("대상 언어의 키가 없고 다른 언어만 존재하면") {
            then("korean 으로 폴백한다") {
                LocalizedText(
                    korean = "김치찌개",
                    translations = mapOf(LanguageCode.EN to "Kimchi Stew"),
                ).resolve(LanguageCode.RU) shouldBe "김치찌개"
            }
        }

        `when`("번역 맵이 비어 있으면") {
            then("어떤 대상 언어든 korean 으로 폴백한다") {
                val text = LocalizedText(korean = "김치찌개")

                text.resolve(LanguageCode.EN) shouldBe "김치찌개"
                text.resolve(LanguageCode.JA) shouldBe "김치찌개"
            }
        }
    }

    given("LocalizedText 불변식") {
        `when`("korean 이 빈 문자열이면") {
            then("생성 시 예외를 던진다") {
                shouldThrow<IllegalArgumentException> { LocalizedText(korean = "") }
            }
        }

        `when`("korean 이 공백뿐이면") {
            then("생성 시 예외를 던진다") {
                shouldThrow<IllegalArgumentException> { LocalizedText(korean = "   ") }
            }
        }
    }
})
