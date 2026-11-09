package com.kbap.common.infra.llm.translation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.domain.LanguageCode
import com.kbap.common.port.llm.TranslationRejectedException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ProcessedFoodTranslationPromptTest : BehaviorSpec({
    fun one(item: String): String = jacksonObjectMapper().writeValueAsString(listOf(item))

    given("가공식품 번역문 단위 검증 — Codex P2(#465 vfJl2)") {
        `when`("단위 글자가 라틴 확장 문자(악센트) 낱말 안에 붙어 있으면") {
            then("단위로 세지 않아 정상 번역이 통과한다") {
                listOf(
                    Triple("밀, 땅콩 함유", "Có chứa lúa mì và lạc", LanguageCode.VI),
                    Triple("쌀", "gạo", LanguageCode.VI),
                    Triple("우유 함유", "Contiene lácteos", LanguageCode.ES),
                    Triple("대두레시틴", "lécithine de soja", LanguageCode.EN),
                ).forEach { (source, output, target) ->
                    ProcessedFoodTranslationPrompt.parse(one(output), source, target).text shouldBe output
                }
            }
        }
        `when`("실제 단위가 원문과 다르면") {
            then("띄어 쓴 단위도, 숫자에 붙은 단위도 계속 거절한다") {
                shouldThrow<IllegalStateException> { ProcessedFoodTranslationPrompt.parse(one("đường 100 ml"), "설탕 100 g", LanguageCode.VI) }
                shouldThrow<IllegalStateException> { ProcessedFoodTranslationPrompt.parse(one("azúcar 100ml"), "설탕 100g", LanguageCode.ES) }
            }
        }
        `when`("원문 단위가 한글에 바로 붙어 있으면(100g당)") {
            then("한글은 단위 경계로 보지 않아 원문 단위로 세고, 같은 단위의 번역은 통과한다") {
                ProcessedFoodTranslationPrompt.parse(one("per 100 g"), "100g당", LanguageCode.EN).text shouldBe "per 100 g"
            }
        }
        `when`("실제 단위가 같으면") {
            then("라틴 확장 문자 낱말과 섞여도 통과한다") {
                ProcessedFoodTranslationPrompt.parse(one("lạc rang 5 ml, gạo 100g"), "볶은 땅콩 5 ml, 쌀 100g", LanguageCode.VI).text shouldBe "lạc rang 5 ml, gạo 100g"
            }
        }
    }
    given("가공식품 번역문 숫자 검증 — 소수점·천 단위 표기(공부 #465·보안 검토)") {
        `when`("소수점을 쉼표로 쓰는 언어(es·ru·vi·id)로 번역하면") {
            then("원문 1.5 를 1,5 로 쓴 번역은 통과하고, 다른 숫자는 거절한다") {
                ProcessedFoodTranslationPrompt.parse(one("azúcar 1,5 g"), "설탕 1.5 g", LanguageCode.ES).text shouldBe "azúcar 1,5 g"
                ProcessedFoodTranslationPrompt.parse(one("сахар 1,5 g"), "설탕 1.5 g", LanguageCode.RU).text shouldBe "сахар 1,5 g"
                shouldThrow<IllegalStateException> { ProcessedFoodTranslationPrompt.parse(one("azúcar 2,5 g"), "설탕 1.5 g", LanguageCode.ES) }
            }
            then("프롬프트대로 원문 숫자 표기를 그대로 둔 번역(1,000 mg)도 통과한다 — Codex P2(#465 vfwr_)") {
                ProcessedFoodTranslationPrompt.parse(one("sodio 1,000 mg"), "나트륨 1,000 mg", LanguageCode.ES).text shouldBe "sodio 1,000 mg"
                ProcessedFoodTranslationPrompt.parse(one("đường 1.5 g"), "설탕 1.5 g", LanguageCode.VI).text shouldBe "đường 1.5 g"
            }
            then("원문 천 단위 1,000 을 그 언어의 천 단위 1.000 으로 쓴 번역은 통과한다") {
                ProcessedFoodTranslationPrompt.parse(one("sodio 1.000 mg"), "나트륨 1,000 mg", LanguageCode.ES).text shouldBe "sodio 1.000 mg"
            }
        }
        `when`("소수점을 마침표로 쓰는 언어로 번역하면") {
            then("원문 천 단위 1,000 mg 을 1.000 mg(=1 mg)으로 쓴 번역은 거절하고, 1,000 mg 은 통과한다") {
                shouldThrow<IllegalStateException> { ProcessedFoodTranslationPrompt.parse(one("sodium 1.000 mg"), "나트륨 1,000 mg", LanguageCode.EN) }
                ProcessedFoodTranslationPrompt.parse(one("sodium 1,000 mg"), "나트륨 1,000 mg", LanguageCode.EN).text shouldBe "sodium 1,000 mg"
                shouldThrow<IllegalStateException> { ProcessedFoodTranslationPrompt.parse(one("sugar 1,5 g"), "설탕 1.5 g", LanguageCode.EN) }
            }
        }
    }
    given("조리법 단계 번역 — 어순이 바뀐 숫자·단위(KB-1023)") {
        `when`("같은 줄 안에서 숫자 순서만 바뀌면") {
            then("단위 없는 숫자(분·초·와트)의 순서가 바뀐 번역은 통과한다") {
                val source = "전자레인지에 넣고 조리해주세요.(1000W 기준 3분 30초, 700W 기준 4분)\n끓는물(460ml)을 붓고 4분 후 물을 버린다"
                val output = "[\"Microwave for 3 minutes 30 seconds at 1000W (4 minutes at 700W).\",\"After 4 minutes, drain the boiling water (460ml)\"]"
                ProcessedFoodTranslationPrompt.parse(output, source, LanguageCode.EN).text shouldBe
                    "Microwave for 3 minutes 30 seconds at 1000W (4 minutes at 700W).\nAfter 4 minutes, drain the boiling water (460ml)"
            }
        }
        `when`("단위가 붙은 값끼리 자리를 바꾸면(영양·함량 값 뒤바뀜)") {
            then("단위가 다른 값의 교환도, 같은 단위 값의 교환도 거절한다") {
                shouldThrow<TranslationRejectedException> { ProcessedFoodTranslationPrompt.parse(one("sugar 5 g, sodium 100 mg"), "당류 100 g, 나트륨 5 mg", LanguageCode.EN) }
                shouldThrow<TranslationRejectedException> { ProcessedFoodTranslationPrompt.parse(one("flour 5 g, sugar 50 g"), "소맥분 50 g, 설탕 5 g", LanguageCode.EN) }
                ProcessedFoodTranslationPrompt.parse(one("flour 50 g, sugar 5 g"), "소맥분 50 g, 설탕 5 g", LanguageCode.EN).text shouldBe "flour 50 g, sugar 5 g"
            }
        }
        `when`("숫자가 다른 줄로 옮겨 가거나 바뀌면") {
            then("재시도 대상(TranslationRejectedException)으로 거절한다") {
                shouldThrow<TranslationRejectedException> { ProcessedFoodTranslationPrompt.parse("[\"Cook 3 minutes 4\",\"Wait\"]", "3분 조리\n4분 기다린다", LanguageCode.EN) }.message shouldBe "가공식품 번역문의 숫자가 원문과 다르다"
                shouldThrow<TranslationRejectedException> { ProcessedFoodTranslationPrompt.parse(one("Cook 2 minutes"), "3분 조리", LanguageCode.EN) }.message shouldBe "가공식품 번역문의 숫자가 원문과 다르다"
                shouldThrow<TranslationRejectedException> { ProcessedFoodTranslationPrompt.parse("[\"first and second\"]", "첫 줄\n둘째 줄", LanguageCode.EN) }.message shouldBe "가공식품 번역문의 줄 수가 원문과 다르다"
                shouldThrow<TranslationRejectedException> { ProcessedFoodTranslationPrompt.parse("[\"Remove the lid\", null]", "첫 줄\n둘째 줄", LanguageCode.EN) }
            }
        }
    }
    given("한 줄 원문 번역 — 한 항목 JSON 배열로 주고받는다(KB-1023 후속)") {
        `when`("원재료·알레르기·식품 유형 한 줄을 한 항목 배열로 돌려주면") {
            then("그 항목을 번역문으로 받는다") {
                ProcessedFoodTranslationPrompt.parse(one("Noodles: wheat flour (USA, Australia), palm oil (Malaysia), vitamin B2"), "면:소맥분(미국산,호주산),팜유(말레이시아산),비타민B2", LanguageCode.EN).text shouldBe
                    "Noodles: wheat flour (USA, Australia), palm oil (Malaysia), vitamin B2"
                ProcessedFoodTranslationPrompt.parse("```json\n[\"Allergens: wheat, soybeans, milk\"]\n```", "알레르기 유발물질: 밀, 대두, 우유 함유", LanguageCode.EN).text shouldBe "Allergens: wheat, soybeans, milk"
                ProcessedFoodTranslationPrompt.parse(one("揚げ麺"), "유탕면", LanguageCode.JA).text shouldBe "揚げ麺"
            }
        }
        `when`("배열 밖·뒤에 텍스트가 있거나, 항목 안에 줄바꿈(덧붙인 Note 등)이 있으면 — Codex P2(#615)") {
            then("합치지 않고 재시도 대상으로 거절한다") {
                listOf(
                    "Here is the translation:\n[\"Contains wheat\"]",
                    "[\"Contains wheat\"]\nNote: also contains milk",
                    "[\"Contains wheat\"] [\"milk\"]",
                    "Contains wheat\nNote: may contain traces",
                    one("Contains wheat\nNote: may contain milk"),
                    one("Contains\r\nwheat"),
                ).forEach { output ->
                    shouldThrow<TranslationRejectedException> { ProcessedFoodTranslationPrompt.parse(output, "밀 함유", LanguageCode.EN) }
                }
            }
        }
        `when`("숫자·단위가 다르거나 항목이 언어 헤더로 시작하면") {
            then("거절한다") {
                shouldThrow<TranslationRejectedException> { ProcessedFoodTranslationPrompt.parse(one("Sodium 500 mg"), "나트륨 600 mg", LanguageCode.EN) }
                shouldThrow<TranslationRejectedException> { ProcessedFoodTranslationPrompt.parse(one("Sodium 600 g"), "나트륨 600 mg", LanguageCode.EN) }
                shouldThrow<TranslationRejectedException> { ProcessedFoodTranslationPrompt.parse(one("en"), "밀 함유", LanguageCode.EN) }
            }
        }
    }
})
