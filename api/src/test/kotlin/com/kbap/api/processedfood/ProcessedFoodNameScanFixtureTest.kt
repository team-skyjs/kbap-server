package com.kbap.api.processedfood

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.food.FoodTestSeed
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import java.util.zip.GZIPInputStream
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodNameScanFixtureTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var nameScanService: ProcessedFoodNameScanService

    init {
        fun resourceLines(name: String, gzip: Boolean = false): List<String> {
            val stream = requireNotNull(javaClass.getResourceAsStream("/processed-food-scan/$name")) { name }
            return (if (gzip) GZIPInputStream(stream) else stream).bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }
        }

        beforeSpec {
            TestTables.clearAll(dataSource)
            dataSource.connection.use { c ->
                c.createStatement().use { it.execute("DELETE FROM processed_food_product") }
                c.prepareStatement(
                    "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, seller, maker_supplement, " +
                        "source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, '과자', NULLIF(?, ''), NULLIF(?, ''), NULLIF(?, ''), 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
                ).use { ps ->
                    resourceLines("products.tsv.gz", gzip = true).forEach { line ->
                        val cols = line.split('\t') + List(5) { "" }
                        ps.setString(1, cols[0])
                        ps.setString(2, cols[1])
                        ps.setString(3, ProcessedFoodSnapshot.normalizeName(cols[1]))
                        ps.setString(4, cols[2])
                        ps.setString(5, cols[3])
                        ps.setString(6, cols[4])
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                c.prepareStatement(
                    "INSERT IGNORE INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, " +
                        "source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 'C002', REPEAT('a', 64), NOW(6), NOW(6) - INTERVAL 30 DAY, 'ACTIVE', NOW(6), NOW(6))",
                ).use { ps ->
                    requireNotNull(javaClass.getResourceAsStream("/processed-food/c002-popular-products.tsv")).bufferedReader().useLines { lines ->
                        lines.drop(1).filter { it.isNotBlank() }.forEach { line ->
                            val (reportNo, name, type, maker, raw) = line.split('\t')
                            ps.setString(1, reportNo)
                            ps.setString(2, name)
                            ps.setString(3, ProcessedFoodSnapshot.normalizeName(name))
                            ps.setString(4, type)
                            ps.setString(5, maker)
                            ps.setString(6, raw)
                            ps.addBatch()
                        }
                    }
                    ps.executeBatch()
                }
            }
            FoodTestSeed.seedMemberAvoiding(dataSource, 81301L)
        }
        afterSpec {
            TestTables.clearAll(dataSource)
            dataSource.connection.use { c -> c.createStatement().use { it.execute("DELETE FROM processed_food_product") } }
        }

        given("합성 OCR fixture 30개(dev 미러와 같은 HACCP 4,578행 + C002 인기 상품 위, 1차 게이트 — 실기 30개는 F2 앱 QA 때 2차)") {
            `when`("이름 스캔을 돌리면") {
                then("상위 5 안에 정답 제품명이 있는 경우가 24개(80%) 이상이다") {
                    val fixtures = resourceLines("ocr-fixtures.tsv").drop(1).map { it.split('\t') }
                    val report = fixtures.map { (id, ocr, expected) ->
                        val candidates = nameScanService.scanName(81301L, ocr.replace("\\n", "\n"), "ko").candidates
                        val names = candidates.map { ProcessedFoodSnapshot.normalizeName(it.productName) }
                        Triple(id, expected in names, "${ocr.replace("\\n", " / ")} => ${candidates.joinToString(" | ") { it.productName }}")
                    }
                    report.forEach { (id, hit, detail) -> println("FIXTURE $id ${if (hit) "HIT " else "MISS"} $detail") }
                    report.count { it.second } shouldBeGreaterThanOrEqual 24
                }
            }
            `when`("C002 로 들어온 인기 상품 이름을 스캔하면(KB-859)") {
                then("그 제품이 1위다") {
                    listOf("농심" to "얼큰한너구리", "그래미" to "여명808", "오리온" to "꼬북칩초코츄러스맛", "롯데" to "젤리먹은스크류바").forEach { (maker, name) ->
                        val first = nameScanService.scanName(81301L, "$maker\n$name\n120g", "ko").candidates.first()
                        ProcessedFoodSnapshot.normalizeName(first.productName) shouldBe name
                    }
                }
            }
        }
    }
}
