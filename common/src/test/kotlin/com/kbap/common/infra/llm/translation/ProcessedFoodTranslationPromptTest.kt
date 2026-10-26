package com.kbap.common.infra.llm.translation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ProcessedFoodTranslationPromptTest : BehaviorSpec({
    given("가공식품 번역문 단위 검증 — Codex P2(#465 vfJl2)") {
        `when`("단위 글자가 라틴 확장 문자(악센트) 낱말 안에 붙어 있으면") {
            then("단위로 세지 않아 정상 번역이 통과한다") {
                listOf(
                    "밀, 땅콩 함유" to "Có chứa lúa mì và lạc",
                    "쌀" to "gạo",
                    "우유 함유" to "Contiene lácteos",
                    "대두레시틴" to "lécithine de soja",
                ).forEach { (source, output) ->
                    ProcessedFoodTranslationPrompt.parse(output, source).text shouldBe output
                }
            }
        }
        `when`("실제 단위가 원문과 다르면") {
            then("띄어 쓴 단위도, 숫자에 붙은 단위도 계속 거절한다") {
                shouldThrow<IllegalStateException> { ProcessedFoodTranslationPrompt.parse("đường 100 ml", "설탕 100 g") }
                shouldThrow<IllegalStateException> { ProcessedFoodTranslationPrompt.parse("azúcar 100ml", "설탕 100g") }
            }
        }
        `when`("원문 단위가 한글에 바로 붙어 있으면(100g당)") {
            then("한글은 단위 경계로 보지 않아 원문 단위로 세고, 같은 단위의 번역은 통과한다") {
                ProcessedFoodTranslationPrompt.parse("per 100 g", "100g당").text shouldBe "per 100 g"
            }
        }
        `when`("실제 단위가 같으면") {
            then("라틴 확장 문자 낱말과 섞여도 통과한다") {
                ProcessedFoodTranslationPrompt.parse("lạc rang 5 ml, gạo 100g", "볶은 땅콩 5 ml, 쌀 100g").text shouldBe "lạc rang 5 ml, gạo 100g"
            }
        }
    }
})
