package com.kbap.api.food

import com.kbap.common.core.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import javax.sql.DataSource

@SpringBootTest
@Import(MySqlContainerConfig::class)
class IngredientRenameMigrationTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        fun columnExists(table: String, column: String): Boolean =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    SELECT COUNT(*) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                    """,
                ).use { ps ->
                    ps.setString(1, table)
                    ps.setString(2, column)
                    ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) > 0 }
                }
            }

        fun tableExists(table: String): Boolean =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    SELECT COUNT(*) FROM information_schema.TABLES
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
                    """,
                ).use { ps ->
                    ps.setString(1, table)
                    ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) > 0 }
                }
            }

        fun koreanNameOf(code: String): String? =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT korean_name FROM ingredients WHERE code = ?").use { ps ->
                    ps.setString(1, code)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }

        given("음식 재료 명칭 개명 마이그레이션") {
            `when`("food 테이블 컬럼을 보면") {
                then("ingredient 로 개명되고 구 컬럼은 남지 않는다") {
                    columnExists("food", "ingredient") shouldBe true
                    columnExists("food", "avoidance_substances") shouldBe false
                }
            }

            `when`("성분 카탈로그 테이블을 보면") {
                then("ingredients 로 개명되고 구 테이블은 남지 않는다") {
                    tableExists("ingredients") shouldBe true
                    tableExists("avoidance_substance") shouldBe false
                }
            }

            `when`("개명 이전 마이그레이션이 적재한 카탈로그 시드를 조회하면") {
                then("데이터가 값 변경 없이 보존된다 — RENAME 은 값을 건드리지 않는다") {
                    koreanNameOf("EGG") shouldBe "계란"
                }
            }
        }
    }
}
