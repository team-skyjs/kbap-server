package com.kbap.common.infra.llm.translation

import com.kbap.common.domain.LanguageCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ProcessedFoodTranslationPromptTest : BehaviorSpec({
    given("가공식품 번역문 단위 검증 — Codex P2(#465 vfJl2)") {
        `when`("단위 글자가 라틴 확장 문자(악센트) 낱말 안에 붙어 있으면") {
            then("단위로 세지 않아 정상 번역이 통과한다") {
                listOf(
                    Triple("밀, 땅콩 함유", "Có chứa lúa mì và lạc", LanguageCode.VI),
                    Triple("쌀", "gạo", LanguageCode.VI),
                    Triple("우유 함유", "Contiene lácteos", LanguageCode.ES),
                    Triple("대두레시틴", "lécithine de soja", LanguageCode.EN),
                ).forEach { (source, output, target) ->
                    ProcessedFoodTranslationPrompt.parse(output, source, target).text shouldBe output
                }
            }
        }
        `when`("실제 단위가 원문과 다르면") {
            then("띄어 쓴 단위도, 숫자에 붙은 단위도 계속 거절한다") {
                shouldThrow<IllegalStateException> { ProcessedFoodTranslationPrompt.parse("đường 100 ml", "설탕 100 g", LanguageCode.VI) }
                shouldThrow<IllegalStateException> { ProcessedFoodTranslationPrompt.parse("azúcar 100ml", "설탕 100g", LanguageCode.ES) }
            }
        }
        `when`("원문 단위가 한글에 바로 붙어 있으면(100g당)") {
            then("한글은 단위 경계로 보지 않아 원문 단위로 세고, 같은 단위의 번역은 통과한다") {
                ProcessedFoodTranslationPrompt.parse("per 100 g", "100g당", LanguageCode.EN).text shouldBe "per 100 g"
            }
        }
        `when`("실제 단위가 같으면") {
            then("라틴 확장 문자 낱말과 섞여도 통과한다") {
                ProcessedFoodTranslationPrompt.parse("lạc rang 5 ml, gạo 100g", "볶은 땅콩 5 ml, 쌀 100g", LanguageCode.VI).text shouldBe "lạc rang 5 ml, gạo 100g"
            }
        }
    }
    given("가공식품 번역문 숫자 검증 — 소수점·천 단위 표기(공부 #465·보안 검토)") {
        `when`("소수점을 쉼표로 쓰는 언어(es·ru·vi·id)로 번역하면") {
            then("원문 1.5 를 1,5 로 쓴 번역은 통과하고, 다른 숫자는 거절한다") {
                ProcessedFoodTranslationPrompt.parse("azúcar 1,5 g", "설탕 1.5 g", LanguageCode.ES).text shouldBe "azúcar 1,5 g"
                ProcessedFoodTranslationPrompt.parse("сахар 1,5 g", "설탕 1.5 g", LanguageCode.RU).text shouldBe "сахар 1,5 g"
                shouldThrow<IllegalStateException> { ProcessedFoodTranslationPrompt.parse("azúcar 2,5 g", "설탕 1.5 g", LanguageCode.ES) }
            }
            then("원문 천 단위 1,000 을 그 언어의 천 단위 1.000 으로 쓴 번역은 통과한다") {
                ProcessedFoodTranslationPrompt.parse("sodio 1.000 mg", "나트륨 1,000 mg", LanguageCode.ES).text shouldBe "sodio 1.000 mg"
            }
        }
        `when`("소수점을 마침표로 쓰는 언어로 번역하면") {
            then("원문 천 단위 1,000 mg 을 1.000 mg(=1 mg)으로 쓴 번역은 거절하고, 1,000 mg 은 통과한다") {
                shouldThrow<IllegalStateException> { ProcessedFoodTranslationPrompt.parse("sodium 1.000 mg", "나트륨 1,000 mg", LanguageCode.EN) }
                ProcessedFoodTranslationPrompt.parse("sodium 1,000 mg", "나트륨 1,000 mg", LanguageCode.EN).text shouldBe "sodium 1,000 mg"
                shouldThrow<IllegalStateException> { ProcessedFoodTranslationPrompt.parse("sugar 1,5 g", "설탕 1.5 g", LanguageCode.EN) }
            }
        }
    }
})
