package com.kbap.common.infra.llm.translation

import com.kbap.common.domain.LanguageCode
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ProcessedFoodNamePromptTest : BehaviorSpec({
    given("가공식품 이름 번역 규칙(KB-953)") {
        `when`("프롬프트를 만들면") {
            then("뜻 번역 금지·공식 라틴 표기 우선·모르면 로마자·설명어만 번역·브랜드는 모든 언어에서 라틴") {
                val prompt = ProcessedFoodNamePrompt.system(LanguageCode.JA, "자가비 짭짤한 맛")
                prompt.contains("Do not translate the meaning of the brand") shouldBe true
                prompt.contains("Revised Romanization of Korean") shouldBe true
                prompt.contains("Never invent an English name") shouldBe true
                prompt.contains("Translate only descriptive words") shouldBe true
                prompt.contains("Keep the brand part in Latin letters for every target language") shouldBe true
                prompt.contains("The official Latin name of this product's brand is Jagabee") shouldBe true
                ProcessedFoodNamePrompt.system(LanguageCode.EN, "예감").contains("The official Latin name of this product's brand") shouldBe false
            }
        }
        `when`("공식 표기를 찾으면") {
            then("포장 표기가 확실한 브랜드만 사전에서 찾고 모르면 null") {
                ProcessedFoodNamePrompt.officialLatinOf("빈츠") shouldBe "Binch"
                ProcessedFoodNamePrompt.officialLatinOf("자가비 짭짤한 맛") shouldBe "Jagabee"
                ProcessedFoodNamePrompt.officialLatinOf("짜파게티") shouldBe "Jjapaghetti"
                ProcessedFoodNamePrompt.officialLatinOf("예감") shouldBe null
            }
        }
        `when`("기대한 번역(en·ja·zh-Hans)을 검사하면") {
            then("전부 통과한다") {
                listOf(
                    "예감" to "Yegam",
                    "자가비 짭짤한 맛" to "Jagabee Salty",
                    "자가비 짭짤한 맛" to "Jagabee しお味",
                    "자가비 짭짤한 맛" to "Jagabee 咸味",
                    "빈츠" to "Binch",
                    "짜파게티" to "Jjapaghetti",
                    "짜파게티" to "Jjapaghetti ジャジャン麺",
                    "예감" to "Yegam 薯片",
                ).forEach { (source, output) -> shouldNotThrowAny { ProcessedFoodNamePrompt.parse(output, source) } }
            }
        }
        `when`("뜻으로 옮긴 번역(dev 실측)을 검사하면") {
            then("거절한다 — 원문 첫 소리와 안 맞음·공식 표기 누락·라틴 브랜드 없음·물음표·여러 줄") {
                listOf(
                    "예감" to "Premonition",
                    "자가비 짭짤한 맛" to "Salty flavor of self-heating?",
                    "빈츠" to "Beans",
                    "예감" to "预感",
                    "예감" to "予感",
                    "짜파게티" to "Jjapaghetti\nnoodles",
                ).forEach { (source, output) -> shouldThrowAny { ProcessedFoodNamePrompt.parse(output, source) } }
            }
        }
    }
})
