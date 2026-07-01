package com.meogo.app.api.avoidance

import com.meogo.core.avoidance.AvoidanceSubstanceCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder

class AvoidanceCatalogSeedSyncTest : BehaviorSpec({
    val seedResourcePath = "db/migration/V5__create_avoidance_catalog_and_mapping.sql"
    val sql = Thread.currentThread().contextClassLoader.getResource(seedResourcePath)?.readText() ?: ""

    val substanceRowRegex = Regex(
        "\\(\\s*" + (1..11).joinToString("\\s*,\\s*") { "'([^']*)'" } + "\\s*\\)",
    )
    val seedCodes = substanceRowRegex.findAll(sql).map { it.groupValues[1] }.toList()

    given("V5 시드 SQL 의 성분 카탈로그") {
        `when`("성분 코드 집합을 식별자 enum 과 비교하면") {
            then("AvoidanceSubstanceCode.entries 의 name 집합과 정확히 일치한다(누락·초과 0)") {
                seedCodes shouldContainExactlyInAnyOrder
                    AvoidanceSubstanceCode.entries.map { it.name }
            }
        }
    }
})
