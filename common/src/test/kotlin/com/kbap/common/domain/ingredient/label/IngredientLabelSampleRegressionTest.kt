package com.kbap.common.domain.ingredient.label

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class IngredientLabelSampleRegressionTest : BehaviorSpec({
    fun rows(name: String): List<Map<String, String>> {
        val lines = requireNotNull(javaClass.getResourceAsStream("/ingredient-label/$name")) { name }.bufferedReader(Charsets.UTF_8).readLines()
        val header = lines.first().split('\t')
        return lines.drop(1).filter { it.isNotBlank() }.map { line -> header.zip(line.split('\t') + List(header.size) { "" }).toMap() }
    }

    given("dev 가공식품 200건 수동 대조 결과(2026-10-20, CRC32(report_no) 순 고정 샘플)") {
        `when`("판독기로 다시 읽으면") {
            then("대조표에서 확정한 알레르기 표시 여부·출처별 검출 코드와 한 행도 다르지 않다") {
                val expected = rows("sample-200-expected.tsv").associateBy { it.getValue("report_no") }
                val sample = rows("sample-200.tsv")
                sample.size shouldBe 200
                val diffs = sample.mapNotNull { row ->
                    val reading = IngredientLabelReader.read(row["allergens"], row["raw_materials"])
                    fun codesFrom(source: IngredientLabelSource) =
                        reading.detections.filter { it.source == source }.map { it.code.name }.sorted().joinToString(",")
                    val actual = listOf(reading.declaration.name, codesFrom(IngredientLabelSource.ALLERGEN_DECLARATION), codesFrom(IngredientLabelSource.RAW_MATERIALS))
                    val want = expected.getValue(row.getValue("report_no")).let { listOf(it["declaration"], it["allergen_codes"], it["raw_codes"]) }
                    if (actual == want) null else "${row["report_no"]} want=$want actual=$actual"
                }
                diffs shouldBe emptyList()
            }
        }
    }
})
