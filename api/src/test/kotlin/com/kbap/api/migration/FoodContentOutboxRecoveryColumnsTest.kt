package com.kbap.api.migration

import com.kbap.api.IntegrationTest
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import javax.sql.DataSource

@IntegrationTest
class FoodContentOutboxRecoveryColumnsTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var dataSource: DataSource

    private fun columns(): Map<String, String> =
        dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT column_name, is_nullable FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'food_content_outbox'
                    """,
                ).use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1).lowercase() to rs.getString(2) else null }.toMap()
                }
            }
        }

    init {
        given("콘텐츠 아웃박스 회수 컬럼") {
            `when`("마이그레이션이 적용되면") {
                then("dead_at·last_error 가 NULL 허용으로 생긴다 — 기존 행은 그대로 둔다") {
                    val columns = columns()
                    columns["dead_at"] shouldBe "YES"
                    columns["last_error"] shouldBe "YES"
                }

                then("굳은 행 조회용 인덱스가 생긴다") {
                    val exists = dataSource.connection.use { c ->
                        c.createStatement().use { st ->
                            st.executeQuery(
                                """
                                SELECT COUNT(*) FROM information_schema.statistics
                                WHERE table_schema = DATABASE() AND table_name = 'food_content_outbox'
                                  AND index_name = 'idx_food_content_outbox_status_sent'
                                """,
                            ).use { rs -> rs.next(); rs.getInt(1) > 0 }
                        }
                    }
                    exists shouldBe true
                }
            }
        }
    }
}
