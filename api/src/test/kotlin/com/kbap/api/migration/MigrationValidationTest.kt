package com.kbap.api.migration

import com.kbap.api.IntegrationTest
import com.kbap.common.domain.ingredient.model.IngredientCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import javax.sql.DataSource

@IntegrationTest
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

            `when`("마스터 시드가 채운 기피물질 카탈로그를 확인하면") {
                then("식별자 enum 전 종과 모든 행의 9개 언어 번역이 온전하다") {
                    dataSource.connection.use { conn ->
                        conn.createStatement().use { st ->
                            st.executeQuery(
                                "SELECT COUNT(*) AS cnt FROM ingredients WHERE status = 'ACTIVE'",
                            ).use { rs ->
                                rs.next()
                                rs.getInt("cnt") shouldBe IngredientCode.entries.size
                            }
                            st.executeQuery("SELECT COUNT(*) FROM ingredients WHERE JSON_LENGTH(translations) <> 9").use { rs ->
                                rs.next()
                                rs.getInt(1) shouldBe 0
                            }
                            st.executeQuery(
                                "SELECT korean_name, JSON_LENGTH(translations) AS langs " +
                                    "FROM ingredients WHERE code = 'EGG'",
                            ).use { rs ->
                                rs.next() shouldBe true
                                rs.getString("korean_name") shouldBe "계란"
                                rs.getInt("langs") shouldBe 9
                            }
                        }
                    }
                }
            }
        }
    }
}
