package com.meogo.app.api.migration

import com.meogo.infra.persistence.testsupport.MySqlIntegrationSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import javax.sql.DataSource

class MigrationValidationTest : MySqlIntegrationSpec() {

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        given("운영 Flyway 마이그레이션이 적용된 MySQL 컨테이너") {
            `when`("flyway_schema_history 를 조회하면") {
                then("모든 마이그레이션이 성공 상태로 적용돼 있다") {
                    dataSource.connection.use { conn ->
                        conn.createStatement().use { st ->
                            val rs = st.executeQuery(
                                "SELECT COUNT(*) AS total, SUM(success) AS ok FROM flyway_schema_history",
                            )
                            rs.next()
                            val total = rs.getInt("total")
                            val ok = rs.getInt("ok")
                            total shouldBeGreaterThan 0
                            ok shouldBe total
                        }
                    }
                }
            }

            `when`("마이그레이션이 만든 MySQL 전용 JSON 컬럼을 확인하면") {
                then("번역 JSON 컬럼(food.name_translations·food.description_translations·avoidance_substance.translations)이 모두 JSON 타입이다") {
                    val jsonColumns = listOf(
                        "food" to "name_translations",
                        "food" to "description_translations",
                        "avoidance_substance" to "translations",
                    )
                    dataSource.connection.use { conn ->
                        jsonColumns.forEach { (table, column) ->
                            conn.createStatement().use { st ->
                                val rs = st.executeQuery(
                                    "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS " +
                                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '$table' " +
                                        "AND COLUMN_NAME = '$column'",
                                )
                                rs.next() shouldBe true
                                rs.getString("DATA_TYPE") shouldBe "json"
                            }
                        }
                    }
                }
            }
        }
    }
}
