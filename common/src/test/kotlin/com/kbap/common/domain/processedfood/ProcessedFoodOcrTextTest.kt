package com.kbap.common.domain.processedfood

import com.kbap.common.domain.processedfood.model.ProcessedFoodOcrText
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

class ProcessedFoodOcrTextTest : BehaviorSpec({
    given("가공식품 앞면 OCR 텍스트") {
        `when`("줄마다 브랜드·제품명·용량·'NEW'가 섞여 있으면") {
            then("용량·NEW 를 지운 줄 질의와 전체 질의를 만든다") {
                val queries = ProcessedFoodOcrText.queriesOf("NEW\n농심\n신라면\n120g")
                queries shouldContain "농심"
                queries shouldContain "신라면"
                queries shouldContain "농심 신라면"
                queries.none { "120" in it || "NEW" in it } shouldBe true
            }
        }
        `when`("전각 문자·용량 변형(1.5L·66 g·4개입·500ml)이 있으면") {
            then("NFKC 로 바꾸고 용량을 모두 지운다") {
                ProcessedFoodOcrText.queriesOf("ＡＢＣ과자 1.5L 66 g 4개입 500ml") shouldBe listOf("ABC과자")
            }
        }
        `when`("'오리지널'과 맛 접미가 붙은 줄이면") {
            then("오리지널은 지우고, 맛 낱말은 그대로인 질의와 뗀 질의를 둘 다 만든다(맛 변형도 후보로)") {
                val queries = ProcessedFoodOcrText.queriesOf("꼬깔콘 고소한맛\n포카칩 오리지널")
                queries shouldContain "꼬깔콘 고소한맛"
                queries shouldContain "꼬깔콘"
                queries shouldContain "포카칩"
                queries.none { "오리지널" in it } shouldBe true
            }
        }
        `when`("글자가 없는 줄·한 글자 줄·빈 텍스트이면") {
            then("질의에서 뺀다") {
                ProcessedFoodOcrText.queriesOf("  \n120g\n-\n가\n") shouldBe emptyList()
                ProcessedFoodOcrText.queriesOf("") shouldBe emptyList()
            }
        }
        `when`("줄이 아주 많으면") {
            then("질의는 8개까지만 만든다") {
                val queries = ProcessedFoodOcrText.queriesOf((1..20).joinToString("\n") { "제품줄$it" })
                queries.size shouldBe 8
                queries shouldNotContain "제품줄20"
            }
        }
    }
})
