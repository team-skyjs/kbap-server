package com.kbap.api.ingredient

import com.kbap.common.domain.ingredient.model.IngredientCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class IngredientCatalogSeedSyncTest : BehaviorSpec({
    val seedResourcePath = "db/migration/V2026.07.16.21.38.42__seed_avoidance_catalog.sql"
    val sql = Thread.currentThread().contextClassLoader.getResource(seedResourcePath)?.readText() ?: ""

    val substanceRowRegex = Regex("""\(\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'[^']*'\s*\)""")
    val substanceRows = substanceRowRegex.findAll(sql).map { it.groupValues }.toList()
    val seedCodes = substanceRows.map { it[1] }
    val seedKoreanByCode = substanceRows.associate { it[1] to it[2] }

    given("성분 카탈로그 시드 SQL") {
        `when`("성분 코드 집합을 식별자 enum 과 비교하면") {
            then("IngredientCode.entries 의 name 집합과 정확히 일치한다(누락·초과 0)") {
                seedCodes shouldContainExactlyInAnyOrder
                    IngredientCode.entries.map { it.name }
            }
        }

        `when`("식별자 enum 의 개발 가독성 label 을 시드 korean_name 과 비교하면") {
            then("각 코드의 label 이 시드 korean_name 과 일치한다(드리프트 0)") {
                IngredientCode.entries.forEach { code ->
                    code.label shouldBe seedKoreanByCode[code.name]
                }
            }
        }
    }
})
