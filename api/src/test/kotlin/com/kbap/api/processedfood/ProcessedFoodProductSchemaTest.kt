package com.kbap.api.processedfood

import com.kbap.api.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodProductSchemaTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        fun <T> withRollback(block: (Connection) -> T): T = dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                block(c)
            } finally {
                c.rollback()
            }
        }

        fun Connection.insert(reportNo: String, name: String = "새우깡") = prepareStatement(
            """
            INSERT INTO processed_food_product (report_no, product_name, normalized_name, content_hash, collected_at, last_seen_at, created_at, updated_at)
            VALUES (?, ?, ?, REPEAT('a', 64), NOW(6), NOW(6), NOW(6), NOW(6))
            """,
        ).use { ps ->
            ps.setString(1, reportNo); ps.setString(2, name); ps.setString(3, name); ps.executeUpdate()
        }

        given("가공식품 제품 마스터 선행 스키마") {
            `when`("기존 마이그레이션 뒤 적용하면") {
                then("테이블과 수집·보완·변경감지 컬럼, 이름·바코드 조회 인덱스가 있고 기본값은 HACCP·ACTIVE 다") {
                    dataSource.connection.use { c ->
                        val columns = c.createStatement().use { s ->
                            s.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'processed_food_product'")
                                .use { r -> buildList { while (r.next()) add(r.getString(1)) } }
                        }
                        columns shouldContainAll listOf(
                            "report_no", "product_name", "normalized_name", "product_gb", "food_type", "food_type_state",
                            "manufacturer", "seller", "maker_supplement", "maker_checked_at", "barcode", "capacity",
                            "raw_materials", "allergens", "nutrients", "image_url", "meta_image_url",
                            "source", "content_hash", "collected_at", "last_seen_at", "status", "created_at", "updated_at",
                        )
                        val indexes = c.createStatement().use { s ->
                            s.executeQuery("SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'processed_food_product'")
                                .use { r -> buildSet { while (r.next()) add(r.getString(1)) } }
                        }
                        indexes shouldContainAll listOf("uq_processed_food_product_report_no", "idx_processed_food_product_normalized_name", "idx_processed_food_product_barcode")
                    }
                    withRollback { c ->
                        c.insert("2011022182815")
                        c.createStatement().use { s ->
                            s.executeQuery("SELECT source, status FROM processed_food_product WHERE report_no = '2011022182815'").use { r ->
                                r.next() shouldBe true
                                r.getString(1) shouldBe "HACCP"
                                r.getString(2) shouldBe "ACTIVE"
                            }
                        }
                    }
                }
            }

            `when`("같은 품목보고번호를 두 번 넣으면") {
                then("유니크 제약으로 거절한다") {
                    withRollback { c ->
                        c.insert("19830444001108")
                        shouldThrow<SQLException> { c.insert("19830444001108", "새우깡 다른 이름") }.errorCode shouldBe 1062
                    }
                }
            }

            `when`("원재료·영양 원문이 실측 최대(1,777·2,397자)보다 길어도") {
                then("TEXT 컬럼이라 잘리지 않고 저장된다") {
                    withRollback { c ->
                        c.insert("20000000000001")
                        c.prepareStatement("UPDATE processed_food_product SET raw_materials = ?, nutrients = ?, allergens = ? WHERE report_no = '20000000000001'").use { ps ->
                            ps.setString(1, "원".repeat(5000)); ps.setString(2, "영".repeat(5000)); ps.setString(3, "알".repeat(1000)); ps.executeUpdate()
                        }
                        c.createStatement().use { s ->
                            s.executeQuery("SELECT CHAR_LENGTH(raw_materials), CHAR_LENGTH(nutrients), CHAR_LENGTH(allergens) FROM processed_food_product WHERE report_no = '20000000000001'").use { r ->
                                r.next(); r.getInt(1) shouldBe 5000; r.getInt(2) shouldBe 5000; r.getInt(3) shouldBe 1000
                            }
                        }
                    }
                }
            }
        }
    }
}
