package com.kbap.api.processedfood

import com.kbap.common.domain.ingredient.label.IngredientLabelReader
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual

class ProcessedFoodLabelFixtureTest : BehaviorSpec({
    given("dev 가공식품 20개 원문으로 합성한 성분표 OCR(줄바꿈·면 나눔·쉼표→마침표·괄호 오인식)") {
        `when`("성분표 분석의 줄 분류 + 판독기로 읽으면") {
            then("깨끗한 원문 판독과 검출 코드가 같은 제품이 18/20 이상이다") {
                val rows = javaClass.getResource("/processed-food-scan/label-fixtures.tsv")!!.readText().lines().drop(1).filter { it.isNotBlank() }.map { it.split("\t") }
                var same = 0
                rows.forEach { (name, allergens, raw, pages) ->
                    val declared = allergens.takeUnless { it.isBlank() || it == "알수없음" || it == "없음" }
                    val clean = IngredientLabelReader.read(declared, raw.takeUnless { it == "알수없음" }).detections.map { it.code }.toSet()
                    val text = ProcessedFoodLabelText.of(pages.split(" ||| ").map { it.replace("\\n", "\n") })
                    val ocr = IngredientLabelReader.read(text.allergens, text.rawMaterials.ifEmpty { null }).detections.map { it.code }.toSet()
                    if (ocr == clean) same++
                    println("LABELFX\t$name\t${clean.sorted()}\t${ocr.sorted()}\t${if (ocr == clean) "same" else "DIFF missing=${(clean - ocr).sorted()} extra=${(ocr - clean).sorted()}"}")
                }
                println("LABELFX\tsame=$same/${rows.size}")
                same shouldBeGreaterThanOrEqual 18
            }
        }
    }
})
