package com.kbap.api.ingredient

import com.kbap.common.domain.ingredient.model.IngredientCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class DietCategoryMappingSyncTest : BehaviorSpec({
    val seedResourcePath = "db/migration/V2026.07.16.21.38.42__seed_avoidance_catalog.sql"
    val sql = Thread.currentThread().contextClassLoader.getResource(seedResourcePath)?.readText() ?: ""

    val seedRowRegex = Regex("""\(\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'[^']*'\s*\)""")
    val seedCodesInOrder = seedRowRegex.findAll(sql).map { it.groupValues[1] }.toList()

    fun codesOf(vararg seedNumbers: Iterable<Int>): Set<IngredientCode> =
        seedNumbers.flatMap { numbers -> numbers.map { IngredientCode.valueOf(seedCodesInOrder[it - 1]) } }.toSet()

    given("diet 카테고리별 회피 재료 번호표(기획 확정)") {
        `when`("시드 SQL 을 파싱하면") {
            then("재료 81종이 행 순서대로 읽힌다(경로 파손·빈 파일 감지)") {
                seedCodesInOrder.size shouldBe 81
            }
        }

        `when`("번호를 시드 행 순서(1-based)로 코드로 해석해 DietCategory 매핑과 비교하면") {
            then("15종 전 카테고리의 회피 재료 집합이 정확히 일치한다") {
                val plannedMapping = mapOf(
                    DietCategory.VEGAN to codesOf(1..11, 37..66),
                    DietCategory.VEGETARIAN to codesOf(listOf(8, 9, 11), 37..66),
                    DietCategory.LACTO_VEGETARIAN to codesOf(listOf(1, 8, 9, 11), 37..66),
                    DietCategory.OVO_VEGETARIAN to codesOf(2..9, listOf(11), 37..66),
                    DietCategory.PESCATARIAN to codesOf(listOf(8, 9, 11, 59), 61..66),
                    DietCategory.GLUTEN_FREE to codesOf(listOf(26, 28, 29, 30)),
                    DietCategory.LACTOSE_FREE to codesOf(2..7),
                    DietCategory.NO_ALCOHOL to codesOf(78..80),
                    DietCategory.MUSLIM to codesOf(listOf(8, 9, 11, 59, 62, 63, 64), 78..80),
                    DietCategory.HINDU to codesOf(listOf(8, 9, 59, 61, 64)),
                    DietCategory.KOSHER to codesOf(listOf(8, 9, 11), 37..51, listOf(59, 62, 63)),
                    DietCategory.BUDDHIST to codesOf(listOf(1, 8, 9, 11), 37..66, 72..77),
                    DietCategory.JAIN to codesOf(listOf(1, 8, 9, 10, 11), 37..66, 70..76),
                    DietCategory.NUT_ALLERGY to codesOf(13..22),
                    DietCategory.SHELLFISH_ALLERGY to codesOf(37..51),
                )

                DietCategory.entries.size shouldBe plannedMapping.size
                DietCategory.entries.forEach { category ->
                    category.avoidedIngredients shouldContainExactlyInAnyOrder plannedMapping.getValue(category)
                }
            }
        }

        `when`("각 카테고리의 매핑을 보면") {
            then("빈 카테고리가 없다") {
                DietCategory.entries.forEach { category ->
                    category.avoidedIngredients.isNotEmpty() shouldBe true
                }
            }
        }
    }
})
