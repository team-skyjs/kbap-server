package com.kbap.api.processedfood

import com.kbap.api.IntegrationTest
import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.LocalDateTime
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodCandidateIndexTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var products: ProcessedFoodProductJpaRepository

    init {
        val since = LocalDateTime.of(1970, 1, 1, 0, 0)

        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun seed(reportNo: String, name: String, maker: String) = execute(
            "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                "VALUES ('$reportNo', '$name', '$name', '과자', '$maker', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
        )
        fun idOf(reportNo: String): Long = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT id FROM processed_food_product WHERE report_no = '$reportNo'").use { it.next(); it.getLong(1) } } }

        beforeSpec { execute("DELETE FROM processed_food_product") }
        afterSpec { execute("DELETE FROM processed_food_product") }

        given("가공식품 후보 색인(S-848)") {
            `when`("적재 뒤 미러가 새 제품을 넣고 갱신 확인 시점이 지나면") {
                then("다시 적재해 새 제품이 이름·제조사 후보로 나온다") {
                    seed("IDX-1", "바나나킥", "농심")
                    val index = ProcessedFoodCandidateIndex(products, Duration.ZERO)
                    index.nameCandidateIds("바나나", since, 300) shouldBe listOf(idOf("IDX-1"))

                    seed("IDX-2", "바나나맛우유", "빙그레")
                    index.nameCandidateIds("바나나", since, 300).toSet() shouldBe setOf(idOf("IDX-1"), idOf("IDX-2"))
                    index.makerCandidateIds("빙그레", since, 300) shouldBe listOf(idOf("IDX-2"))
                }
            }
            `when`("갱신 확인 간격이 아직 지나지 않았으면") {
                then("DB 를 다시 보지 않고 적재해 둔 목록을 쓴다") {
                    val index = ProcessedFoodCandidateIndex(products, Duration.ofHours(1))
                    val before = index.nameCandidateIds("새우", since, 300)
                    seed("IDX-3", "새우깡", "농심")
                    index.nameCandidateIds("새우", since, 300) shouldBe before
                    ProcessedFoodCandidateIndex(products, Duration.ZERO).nameCandidateIds("새우", since, 300) shouldBe listOf(idOf("IDX-3"))
                }
            }
            `when`("이름이 정확히 같거나 앞이 같거나 짧은 순") {
                then("SQL 과 같은 순서(정확 일치 → 앞 일치 → 길이 → id)로 자른다") {
                    seed("IDX-4", "감자칩오리지널", "오리온")
                    seed("IDX-5", "포카칩", "오리온")
                    seed("IDX-6", "감자칩", "오리온")
                    seed("IDX-7", "왕감자칩", "오리온")
                    ProcessedFoodCandidateIndex(products, Duration.ZERO).nameCandidateIds("감자칩", since, 2) shouldBe listOf(idOf("IDX-6"), idOf("IDX-4"))
                }
            }
        }
    }
}
