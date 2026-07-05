package com.meogo.app.api.migration

import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import javax.sql.DataSource

@SpringBootTest
@Import(MySqlContainerConfig::class)
class MigrationValidationTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

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
        }
    }
}
