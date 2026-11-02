package com.kbap.common.infra.llm.translation

import com.kbap.common.domain.LanguageCode
import io.kotest.assertions.throwables.shouldNotThrowAny
import com.kbap.common.port.llm.TranslationRejectedException
import io.kotest.assertions.throwables.shouldThrow
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
                prompt.contains("The official Latin name of this product's brand or product name is Jagabee") shouldBe true
                ProcessedFoodNamePrompt.system(LanguageCode.EN, "예감").contains("The official Latin name of this product's brand or product name") shouldBe false
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
                    "매운 새우깡" to "Spicy Saeukkang",
                    "매운 새우깡" to "辛い Saeukkang",
                    "무가당 요거트" to "Sugar-free Yogurt",
                    "제로 콜라" to "Zero Cola",
                    "씨제이 햇반" to "CJ Hetbahn",
                    "롯데 빼빼로" to "Lotte Pepero",
                    "바나나맛 우유" to "Banana Milk",
                ).forEach { (source, output) -> shouldNotThrowAny { ProcessedFoodNamePrompt.parse(output, source) } }
            }
        }
        `when`("공식 표기 사전을 보강하면(KB-960)") {
            then("dev 거절 브랜드가 사전에서 잡히고 띄어쓰기와 무관하며, 사전 표기를 쓴 번역은 첫 소리와 달라도 통과한다") {
                ProcessedFoodNamePrompt.officialLatinOf("빙그레 바나나맛 우유") shouldBe "Binggrae Banana Milk"
                ProcessedFoodNamePrompt.officialLatinOf("빙그레바나나맛우유") shouldBe "Binggrae Banana Milk"
                ProcessedFoodNamePrompt.officialLatinOf("환타 오렌지") shouldBe "Fanta"
                ProcessedFoodNamePrompt.officialLatinOf("까르보불닭볶음면") shouldBe "Carbo Buldak"
                ProcessedFoodNamePrompt.officialLatinOf("불닭볶음면") shouldBe "Buldak"
                listOf(
                    "빙그레 바나나맛 우유" to "Binggrae Banana Milk",
                    "환타 오렌지" to "Fanta Orange",
                    "까르보불닭볶음면" to "Carbo Buldak Stir-fried Noodles",
                    "더위사냥" to "Deowi Sanyang",
                    "복음자리 딸기잼" to "Bokumjari Strawberry Jam",
                    "마이쮸 포도" to "Mychew Grape",
                ).forEach { (source, output) -> shouldNotThrowAny { ProcessedFoodNamePrompt.parse(output, source) } }
                listOf(
                    "빙그레 바나나맛 우유" to "Banana Uyu by Binggrae",
                    "환타 오렌지" to "Fantao Orange Flavor",
                    "까르보불닭볶음면" to "Carbo Bullet Bokkeum-myeon",
                    "복음자리 딸기잼" to "Gomjumari Strawberry Jam",
                    "더위사냥" to "The War Summer Hunt",
                    "인생라면" to "Life Ramen",
                ).forEach { (source, output) -> shouldThrow<TranslationRejectedException> { ProcessedFoodNamePrompt.parse(output, source) } }
            }
        }
        `when`("라면을 표기하면(예진 11/2 — 한국 라면은 Ramyeon)") {
            then("Ramyeon 은 통과·Ramyun 은 Ramyeon 으로 바꾸고, 원문에 라멘이 없는데 Ramen 이면 거절, 일본식 라멘만 Ramen 허용") {
                ProcessedFoodNamePrompt.system(LanguageCode.EN, "신라면").contains("Ramyeon") shouldBe true
                ProcessedFoodNamePrompt.officialLatinOf("신라면") shouldBe "Shin Ramyeon"
                ProcessedFoodNamePrompt.parse("Shin Ramyeon", "신라면").text shouldBe "Shin Ramyeon"
                ProcessedFoodNamePrompt.parse("Shin Ramyun", "신라면").text shouldBe "Shin Ramyeon"
                ProcessedFoodNamePrompt.parse("Neoguri Ramyeon Mild", "너구리 순한맛").text shouldBe "Neoguri Ramyeon Mild"
                ProcessedFoodNamePrompt.parse("Miso Ramen", "미소라멘").text shouldBe "Miso Ramen"
                ProcessedFoodNamePrompt.parse("Buldak Bokkeum-myeon", "불닭볶음면").text shouldBe "Buldak Bokkeum-myeon"
                shouldThrow<TranslationRejectedException> { ProcessedFoodNamePrompt.parse("Jin Ramen Mild", "진라면 순한맛") }
                shouldThrow<TranslationRejectedException> { ProcessedFoodNamePrompt.parse("Ottogi Kimchi Ramen", "오뚜기 김치라면") }
            }
        }
        `when`("공식 라틴 표기 바로 옆에 가나·한자 서술어가 붙으면 — Codex P2(#524)") {
            then("라틴 낱말 경계만 보므로 통과하고, 공식 표기가 더 긴 라틴 낱말의 일부면 계속 거절한다") {
                ProcessedFoodNamePrompt.parse("Fantaオレンジ", "환타 오렌지").text shouldBe "Fantaオレンジ"
                ProcessedFoodNamePrompt.parse("Jagabee咸味", "자가비 짭짤한맛").text shouldBe "Jagabee咸味"
                shouldThrow<TranslationRejectedException> { ProcessedFoodNamePrompt.parse("Fantastic Orange", "환타 오렌지") }
                shouldThrow<TranslationRejectedException> { ProcessedFoodNamePrompt.parse("Fanta2 Orange", "환타 오렌지") }
            }
        }
        `when`("우유를 표기하면(예진 11/2 — Uyu 금지)") {
            then("우유는 설명어라 Milk 로 옮기고 Uyu 로마자는 거절한다, 빙그레 바나나맛·딸기맛 우유는 사전 표기") {
                ProcessedFoodNamePrompt.system(LanguageCode.EN, "매일우유").contains("Milk") shouldBe true
                ProcessedFoodNamePrompt.officialLatinOf("빙그레 딸기맛 우유") shouldBe "Binggrae Strawberry Milk"
                shouldNotThrowAny { ProcessedFoodNamePrompt.parse("Maeil Milk", "매일우유") }
                shouldNotThrowAny { ProcessedFoodNamePrompt.parse("Binggrae Strawberry Milk", "빙그레 딸기맛 우유") }
                shouldThrow<TranslationRejectedException> { ProcessedFoodNamePrompt.parse("Maeil Uyu", "매일우유") }
                shouldThrow<TranslationRejectedException> { ProcessedFoodNamePrompt.parse("Binggrae Banana Uyu", "빙그레 바나나맛 우유") }
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
                ).forEach { (source, output) -> shouldThrow<TranslationRejectedException> { ProcessedFoodNamePrompt.parse(output, source) } }
            }
        }
    }
})
