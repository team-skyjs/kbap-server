package com.kbap.common.domain.ingredient.label

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

class DeclaredAllergenCaptureTest : BehaviorSpec({
    val rows = requireNotNull(javaClass.getResourceAsStream("/ingredient-label/declared-allergen-fixture.tsv")) { "declared-allergen-fixture.tsv" }
        .bufferedReader(Charsets.UTF_8)
        .readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split('\t') }
    val mandatory = IngredientLabelReader.mandatoryAllergenCodes()
    val declared = mutableMapOf<String, Int>()
    val captured = mutableMapOf<String, Int>()
    rows.forEach { (_, allergens, rawMaterials) ->
        val fromDeclaration = IngredientLabelReader.read(allergens, null).detections.map { it.code }.filter { it in mandatory }.toSet()
        val fromRaw = IngredientLabelReader.read(null, rawMaterials).detections.map { it.code }.toSet()
        fromDeclaration.forEach { code ->
            declared.merge(code.name, 1, Int::plus)
            if (code in fromRaw) captured.merge(code.name, 1, Int::plus)
        }
    }

    given("제조사가 선언한 알레르겐을 원재료 문장만으로 다시 잡는 비율 — 고정 픽스처 300건") {
        `when`("판독 사전으로 원재료를 읽으면") {
            then("선언 건수는 픽스처 그대로이고 전체 포착 수는 기준선 아래로 내려가지 않는다") {
                declared.values.sum() shouldBe DECLARED_TOTAL
                captured.values.sum() shouldBeGreaterThanOrEqual CAPTURED_TOTAL
            }

            then("코드마다 포착 수가 기준선 아래로 내려가지 않는다 — 한 코드의 퇴보를 다른 코드의 개선이 가리지 못하게") {
                CAPTURED_FLOOR.forEach { (code, floor) ->
                    withClue("$code 포착 ${captured[code] ?: 0}/${declared[code] ?: 0}") { (captured[code] ?: 0) shouldBeGreaterThanOrEqual floor }
                }
            }
        }
    }
}) {
    companion object {
        const val DECLARED_TOTAL = 1012
        const val CAPTURED_TOTAL = 609
        val CAPTURED_FLOOR = mapOf(
            "SOY" to 155, "WHEAT" to 131, "MILK" to 93, "BEEF" to 30, "EGG" to 49, "PORK" to 34, "CHICKEN" to 34,
            "CLAM" to 6, "SHRIMP" to 14, "TOMATO" to 17, "OYSTER" to 2, "PEANUT" to 9, "MUSSEL" to 4, "SQUID" to 6,
            "CRAB" to 5, "WALNUT" to 9, "SULFITES" to 2, "PEACH" to 4, "ABALONE" to 1, "BUCKWHEAT" to 4, "MACKEREL" to 0,
        )
    }
}
