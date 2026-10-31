package com.kbap.api.processedfood

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodNameScanTypoTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var service: ProcessedFoodService

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun seed(reportNo: String, name: String, maker: String) = execute(
            "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, allergens, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                "VALUES ('$reportNo', '$name', '${ProcessedFoodSnapshot.normalizeName(name)}', '음료', '$maker', '정제수, 바나나', NULL, 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
        )
        fun candidates(vararg lines: String) = service.getNameScanCandidates(lines.toList(), 5).products.map { it.reportNo }

        beforeSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            seed("TY-1", "빙그레 바나나맛 우유", "빙그레")
            seed("TY-2", "빙그레 딸기맛 우유", "빙그레")
            seed("TY-3", "남양 바나나맛 우유", "남양유업")
            seed("TY-4", "빙그레 바나나맞춤 젤리", "빙그레")
            seed("TY-5", "노브랜드 딸기맛 우유", "노브랜드")
            seed("TY-6", "감자스틱", "태광")
            seed("TY-7", "태광", "다른제과")
        }
        afterSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }

        given("이름 스캔 OCR 한 글자 오인식(KB-946)") {
            `when`("제조사 줄과 함께 첫 글자가 틀린 상품명 줄이 오면(바→아·가·하)") {
                then("바나나맛 우유를 후보로 찾고, 다른 제조사의 바나나맛 우유는 끌어오지 않는다") {
                    listOf(arrayOf("빙그레", "아나나맛"), arrayOf("빙그레 가나나맛"), arrayOf("G빙그레", "하나나맛")).forEach { lines ->
                        val found = candidates(*lines)
                        found shouldContain "TY-1"
                        found shouldNotContain "TY-3"
                    }
                }
            }
            `when`("교정된 상품명이 대표 상품 별칭과 겹치면 — Codex P2(#515)") {
                then("문맥 게이트를 통과한 상품에만 부스트를 준다 — 다른 브랜드 대표 상품을 끌어올리지 않는다") {
                    val noBrand = candidates("노브랜드 딸기맞")
                    noBrand.first() shouldBe "TY-5"
                    noBrand shouldNotContain "TY-2"

                    val boosted = service.getNameScanCandidates(listOf("빙그레 바나나맡"), 5)
                    boosted.products.first().reportNo shouldBe "TY-1"
                    boosted.boostedReportNos shouldBe listOf("TY-1")
                }
            }
            `when`("같은 줄에 정확한 철자와 한 글자 틀린 철자가 함께 있으면 — Codex P2(#515 2R)") {
                then("교정 토큰을 중복으로 더하지 않아 정확 일치 상품이 그대로 1위다") {
                    candidates("감자스틱 감자스딕 태광").first() shouldBe "TY-6"
                }
                then("교정 토큰이 원문 토큰의 부분 문자열이어도 더하지 않는다 — Codex P2(#515 3R)") {
                    candidates("감자스틱 자스딕 태광").first() shouldBe "TY-6"
                }
            }
            `when`("틀린 상품명 줄만 오고 제조사·다른 토큰이 없으면") {
                then("한 글자 치환을 쓰지 않아 후보가 없다") {
                    candidates("아나나맛") shouldBe emptyList()
                }
            }
            `when`("모든 토큰이 정확히 맞으면") {
                then("치환 후보를 더하지 않고 순위가 그대로다") {
                    candidates("빙그레", "바나나맛") shouldBe listOf("TY-1", "TY-2", "TY-4", "TY-3")
                }
            }
        }
    }
}
