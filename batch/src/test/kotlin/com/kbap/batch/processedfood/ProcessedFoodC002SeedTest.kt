package com.kbap.batch.processedfood

import com.kbap.batch.BatchIntegrationTest
import com.kbap.batch.processedfood.ProcessedFoodMirrorTest.FakeCounter
import com.kbap.batch.processedfood.ProcessedFoodMirrorTest.FakeLookup
import com.kbap.batch.processedfood.ProcessedFoodMirrorTest.FakeSource
import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.common.port.publicdata.HaccpProduct
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.sql.DataSource

@BatchIntegrationTest
class ProcessedFoodC002SeedTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var repository: ProcessedFoodProductJpaRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var dataSource: DataSource

    init {
        val clock = Clock.fixed(Instant.parse("2026-10-22T19:00:00Z"), ZoneId.of("Asia/Seoul"))
        val angry = ProcessedFoodC002Seed.Row("20010549541161", "앵그리짜파구리", "유탕면", "(주)농심", "소맥분(밀:호주산), 팜유")
        val yeomyung = ProcessedFoodC002Seed.Row("200003990392", "여명808", "액상차", "주식회사 그래미", "정제수, 헛개나무열매추출액")

        fun seed(rows: List<ProcessedFoodC002Seed.Row>) = ProcessedFoodC002Seed(repository, TransactionTemplate(transactionManager), clock, rows)
        fun rows(sql: String): List<List<String?>> = dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { r -> generateSequence { if (r.next()) (1..r.metaData.columnCount).map(r::getString) else null }.toList() }
            }
        }

        beforeEach { dataSource.connection.use { c -> c.createStatement().use { it.execute("DELETE FROM processed_food_product") } } }
        afterSpec { dataSource.connection.use { c -> c.createStatement().use { it.execute("DELETE FROM processed_food_product") } } }

        given("C002 인기 상품 목록(KB-859)") {
            `when`("번들 목록을 읽으면") {
                then("고른 104행이고 품목보고번호가 겹치지 않으며 원재료가 모두 있다") {
                    val bundled = ProcessedFoodC002Seed.bundledRows()
                    bundled.size shouldBe 104
                    bundled.map { it.reportNo }.toSet().size shouldBe 104
                    bundled.filter { it.rawMaterials.isBlank() }.shouldBeEmpty()
                }
            }
            `when`("빈 테이블에 적재하면") {
                then("source C002 로 넣고 원재료만 있고 알레르기 표시란·사진은 비운다") {
                    val summary = seed(listOf(angry, yeomyung)).seed()

                    summary.inserted shouldBe 2
                    rows("SELECT report_no, source, normalized_name, manufacturer, raw_materials, allergens, image_url FROM processed_food_product ORDER BY report_no") shouldBe listOf(
                        listOf("200003990392", "C002", "여명808", "주식회사 그래미", "정제수, 헛개나무열매추출액", null, null),
                        listOf("20010549541161", "C002", "앵그리짜파구리", "(주)농심", "소맥분(밀:호주산), 팜유", null, null),
                    )
                }
            }
            `when`("다시 적재하면") {
                then("행이 늘지 않는다") {
                    seed(listOf(angry, yeomyung)).seed()
                    val again = seed(listOf(angry, yeomyung)).seed()

                    again.inserted shouldBe 0
                    rows("SELECT COUNT(*) FROM processed_food_product")[0][0] shouldBe "2"
                }
            }
            `when`("같은 품목보고번호가 HACCP 에 이미 있으면") {
                then("C002 행은 넣지 않고 충돌로 남기며 HACCP 행은 그대로다") {
                    val haccp = HaccpProduct(
                        "20010549541161", "앵그리 짜파구리", "식품", "유탕면", "유처리제품", "㈜농심_구미", "㈜농심_구미", null, "137g", "밀가루", "밀", "총 내용량", null, null,
                    )
                    ProcessedFoodMirror(FakeSource(mapOf("유탕면" to listOf(haccp))), FakeLookup(emptyMap()), repository, TransactionTemplate(transactionManager), clock, emptyList(), 10, 0, counter = FakeCounter()).mirror()

                    val summary = seed(listOf(angry, yeomyung)).seed()

                    summary.inserted shouldBe 1
                    summary.haccpConflicts shouldBe listOf("20010549541161")
                    rows("SELECT source, product_name FROM processed_food_product WHERE report_no = '20010549541161'") shouldBe listOf(listOf("HACCP", "앵그리 짜파구리"))
                }
            }
        }

        given("HACCP 미러가 C002 행과 같은 품목보고번호를 가져오면(KB-859)") {
            `when`("미러링하면") {
                then("덮어쓰지 않고 건너뛰어 C002 행 내용이 그대로다") {
                    seed(listOf(angry)).seed()
                    val haccp = HaccpProduct(
                        "20010549541161", "앵그리 짜파구리 HACCP", "식품", "유탕면", "유처리제품", "㈜농심_구미", "㈜농심_구미", null, "137g", "밀가루", "밀", "총 내용량", "http://img/1.jpg", null,
                    )

                    val summary = ProcessedFoodMirror(FakeSource(mapOf("유탕면" to listOf(haccp))), FakeLookup(emptyMap()), repository, TransactionTemplate(transactionManager), clock, emptyList(), 10, 0, counter = FakeCounter()).mirror()

                    summary.sourceConflicts shouldBe 1
                    summary.changed shouldBe 0
                    rows("SELECT source, product_name, image_url FROM processed_food_product WHERE report_no = '20010549541161'") shouldBe listOf(listOf("C002", "앵그리짜파구리", null))
                }
            }
        }
    }
}
