package com.meogo.app.api.avoidance

import com.meogo.core.avoidance.AvoidanceSubstanceCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class AvoidanceCatalogSeedSyncTest : BehaviorSpec({
    val seedResourcePath = "db/migration/V5__create_avoidance_catalog_and_mapping.sql"
    val sql = Thread.currentThread().contextClassLoader.getResource(seedResourcePath)?.readText() ?: ""

    val substanceRowRegex = Regex(
        "\\(\\s*" + (1..11).joinToString("\\s*,\\s*") { "'([^']*)'" } + "\\s*\\)",
    )
    val substanceRows = substanceRowRegex.findAll(sql).map { it.groupValues }.toList()
    val seedCodes = substanceRows.map { it[1] }
    val seedKoreanByCode = substanceRows.associate { it[1] to it[2] }

    given("V5 시드 SQL 의 성분 카탈로그") {
        `when`("성분 코드 집합을 식별자 enum 과 비교하면") {
            then("AvoidanceSubstanceCode.entries 의 name 집합과 정확히 일치한다(누락·초과 0)") {
                seedCodes shouldContainExactlyInAnyOrder
                    AvoidanceSubstanceCode.entries.map { it.name }
            }
        }

        `when`("식별자 enum 의 개발 가독성 label 을 시드 korean_name 과 비교하면") {
            then("각 코드의 label 이 시드 korean_name 과 일치한다(드리프트 0)") {
                AvoidanceSubstanceCode.entries.forEach { code ->
                    code.label shouldBe seedKoreanByCode[code.name]
                }
            }
        }
    }
})
