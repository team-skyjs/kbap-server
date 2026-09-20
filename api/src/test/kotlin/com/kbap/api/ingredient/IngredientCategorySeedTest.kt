package com.kbap.api.ingredient

import com.kbap.api.IntegrationTest
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import javax.sql.DataSource

@IntegrationTest
class IngredientCategorySeedTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var dataSource: DataSource

    private fun <T> query(sql: String, row: (java.sql.ResultSet) -> T): List<T> =
        dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) row(rs) else null }.toList() }
            }
        }

    init {
        beforeSpec { IngredientTestSeed.restoreCatalog(dataSource) }

        given("재료 분류 시드") {
            `when`("카탈로그를 복원하면") {
                then("분류 11개가 sort_order 10 간격으로 있다") {
                    query("SELECT code, sort_order FROM ingredient_category ORDER BY sort_order") {
                        it.getString(1) to it.getInt(2)
                    } shouldBe listOf(
                        "MEAT", "EGG", "DAIRY", "FISH", "CRUSTACEAN", "MOLLUSK",
                        "GRAIN", "LEGUME", "NUT_SEED", "PRODUCE", "ADDITIVE",
                    ).mapIndexed { i, code -> code to (i + 1) * 10 }
                }

                then("분류가 비어 있는 재료는 포괄 code 두 개뿐이다") {
                    query("SELECT code FROM ingredients WHERE category_id IS NULL ORDER BY code") { it.getString(1) } shouldBe
                        listOf("BROTH", "SEAFOOD")
                }

                then("판단이 애매했던 세 재료는 확정된 분류에 붙는다") {
                    query(
                        """
                        SELECT i.code, c.code FROM ingredients i JOIN ingredient_category c ON c.id = i.category_id
                        WHERE i.code IN ('HONEY', 'DASHI', 'MUSTARD') ORDER BY i.code
                        """,
                    ) { it.getString(1) to it.getString(2) } shouldBe
                        listOf("DASHI" to "FISH", "HONEY" to "MEAT", "MUSTARD" to "NUT_SEED")
                }
            }
        }

        given("음식-재료 관계 테이블의 포함 확률") {
            fun insertable(percent: Int): Boolean =
                dataSource.connection.use { c ->
                    c.createStatement().use {
                        it.execute("SET FOREIGN_KEY_CHECKS = 0")
                        try {
                            runCatching {
                                it.execute(
                                    "INSERT INTO food_ingredient (food_id, ingredient_id, inclusion_percent, sort_order) " +
                                        "VALUES (-1, -1, $percent, 10)",
                                )
                            }.isSuccess
                        } finally {
                            it.execute("DELETE FROM food_ingredient WHERE food_id = -1")
                            it.execute("SET FOREIGN_KEY_CHECKS = 1")
                        }
                    }
                }

            `when`("1..100 이면") {
                then("저장된다") { listOf(1, 100).map(::insertable) shouldBe listOf(true, true) }
            }

            `when`("0 이나 101 이면") {
                then("CHECK 제약이 거절한다") { listOf(0, 101).map(::insertable) shouldBe listOf(false, false) }
            }
        }
    }
}
