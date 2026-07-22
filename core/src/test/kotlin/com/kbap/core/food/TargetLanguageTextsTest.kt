package com.kbap.core.food

import com.kbap.core.lang.LanguageCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class TargetLanguageTextsTest : BehaviorSpec({
    given("TargetLanguageTexts 생성") {
        `when`("9개 대상 언어 전수를 non-blank 값으로 채우면") {
            then("생성에 성공한다") {
                val texts = TargetLanguageTexts.TARGET_LANGUAGES.associateWith { "번역-${it.code}" }
                TargetLanguageTexts(texts).texts.keys shouldBe TargetLanguageTexts.TARGET_LANGUAGES
            }
        }

        `when`("대상 언어 하나가 누락되면") {
            then("예외가 발생한다") {
                val missingOne = TargetLanguageTexts.TARGET_LANGUAGES
                    .drop(1)
                    .associateWith { "번역-${it.code}" }
                shouldThrow<IllegalArgumentException> { TargetLanguageTexts(missingOne) }
            }
        }

        `when`("KO 원문이 포함되면") {
            then("예외가 발생한다") {
                val withKo = (TargetLanguageTexts.TARGET_LANGUAGES + LanguageCode.KO)
                    .associateWith { "번역-${it.code}" }
                shouldThrow<IllegalArgumentException> { TargetLanguageTexts(withKo) }
            }
        }

        `when`("값이 blank 이면") {
            then("예외가 발생한다") {
                val blankValue = TargetLanguageTexts.TARGET_LANGUAGES.associateWith { "" }
                shouldThrow<IllegalArgumentException> { TargetLanguageTexts(blankValue) }
            }
        }
    }

    given("TARGET_LANGUAGES") {
        `when`("대상 언어 집합을 확인하면") {
            then("KO 를 제외한 전체 언어다") {
                TargetLanguageTexts.TARGET_LANGUAGES shouldBe
                    LanguageCode.entries.filterNot { it == LanguageCode.KO }.toSet()
            }
        }
    }
})
