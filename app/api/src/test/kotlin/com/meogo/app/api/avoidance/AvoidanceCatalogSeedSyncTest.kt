package com.meogo.app.api.avoidance

import com.meogo.core.avoidance.AvoidanceSubstance
import com.meogo.core.avoidance.AvoidanceSubstanceTranslations
import com.meogo.core.kernel.lang.LanguageCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class AvoidanceCatalogSeedSyncTest : BehaviorSpec({
    val seedResourcePath = "db/migration/V5__create_avoidance_catalog_and_mapping.sql"
    val sql = Thread.currentThread().contextClassLoader.getResource(seedResourcePath)?.readText() ?: ""

    val translationLanguageOrder = listOf(
        LanguageCode.ZH_HANS,
        LanguageCode.EN,
        LanguageCode.JA,
        LanguageCode.ZH_HANT,
        LanguageCode.VI,
        LanguageCode.ID,
        LanguageCode.TH,
        LanguageCode.RU,
        LanguageCode.ES,
    )

    val substanceRowRegex = Regex(
        "\\(\\s*" + (1..11).joinToString("\\s*,\\s*") { "'([^']*)'" } + "\\s*\\)",
    )
    val substanceRows = substanceRowRegex.findAll(sql).map { it.groupValues }.toList()

    val codeToKorean = substanceRows.associate { it[1] to it[2] }
    val codeToTranslations = substanceRows.associate { row ->
        row[1] to translationLanguageOrder.mapIndexed { index, lang -> lang to row[3 + index] }.toMap()
    }

    val categoryLiteralRegex = Regex("'(ALLERGEN|DIETARY_RULE|PERSONAL_AVOIDANCE)'")
    val codeAssignmentRegex = Regex("code\\s*=\\s*'([A-Z_]+)'")
    val membershipPairs = sql.split(";")
        .filter { it.contains("avoidance_substance_category") && it.contains("INSERT", ignoreCase = true) }
        .mapNotNull { statement ->
            val code = codeAssignmentRegex.find(statement)?.groupValues?.get(1)
            val category = categoryLiteralRegex.find(statement)?.groupValues?.get(1)
            if (code != null && category != null) code to category else null
        }
        .toSet()

    given("V5 시드 SQL 의 성분 카탈로그") {
        `when`("성분 코드 집합을 enum 과 비교하면") {
            then("AvoidanceSubstance.entries 의 name 집합과 정확히 일치한다(누락·초과 0)") {
                codeToKorean.keys shouldContainExactlyInAnyOrder
                    AvoidanceSubstance.entries.map { it.name }
            }
        }

        `when`("각 성분의 korean_name 을 enum 과 비교하면") {
            then("AvoidanceSubstance.koName 과 일치한다") {
                AvoidanceSubstance.entries.forEach { substance ->
                    codeToKorean[substance.name] shouldBe substance.koName
                }
            }
        }

        `when`("각 성분의 9개 번역 컬럼을 비교하면") {
            then("AvoidanceSubstanceTranslations 의 값과 일치한다") {
                AvoidanceSubstance.entries.forEach { substance ->
                    val expected = AvoidanceSubstanceTranslations.translations.getValue(substance)
                    val actual = codeToTranslations[substance.name]
                    translationLanguageOrder.forEach { lang ->
                        actual?.get(lang) shouldBe expected.getValue(lang)
                    }
                }
            }
        }
    }

    given("V5 시드 SQL 의 성분↔분류 멤버십") {
        `when`("(code, category) 집합을 enum 의 categories 전개와 비교하면") {
            then("정확히 일치한다(드리프트 0)") {
                val expected = AvoidanceSubstance.entries.flatMap { substance ->
                    substance.categories.map { substance.name to it.name }
                }

                membershipPairs shouldContainExactlyInAnyOrder expected
            }
        }
    }
})
