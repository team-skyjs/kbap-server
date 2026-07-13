package com.meogo.domain.scan

import com.meogo.core.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import javax.sql.DataSource

@SpringBootTest
@Import(MySqlContainerConfig::class)
class ScanHistoryServiceTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var service: ScanHistoryService

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        fun createFoodTableStub() {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "CREATE TABLE IF NOT EXISTS food (" +
                            "id BIGINT PRIMARY KEY, korean_name VARCHAR(255), description TEXT, spiciness INT, " +
                            "name_translations JSON, description_translations JSON, content_status VARCHAR(20), " +
                            "status VARCHAR(20), created_at DATETIME(6), updated_at DATETIME(6))",
                    )
                }
            }
        }

        fun clearTables() {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM scan_history")
                    statement.execute("DELETE FROM food")
                }
            }
        }

        fun seedFood(id: Long, koreanName: String, contentStatus: String = "READY") {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO food (id, korean_name, description, spiciness, " +
                            "name_translations, description_translations, content_status, status, created_at, updated_at) " +
                            "VALUES ($id, '$koreanName', '설명', 0, '{}', '{}', '$contentStatus', 'ACTIVE', " +
                            "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                    )
                }
            }
        }

        fun seedHistory(memberId: Long, foodId: Long, scannedAt: String) {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO scan_history (member_id, food_id, status, created_at, updated_at) " +
                            "VALUES ($memberId, $foodId, 'ACTIVE', '$scannedAt', '$scannedAt')",
                    )
                }
            }
        }

        fun countHistories(): Int =
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM scan_history").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }

        beforeSpec {
            createFoodTableStub()
        }

        beforeContainer {
            clearTables()
        }

        given("스캔 이력 저장") {
            `when`("여러 건을 saveAll 로 저장하면") {
                then("모두 영속된다") {
                    seedFood(1L, "김치찌개")
                    seedFood(2L, "비빔밥")

                    service.saveAll(
                        listOf(
                            ScanHistory.record(memberId = 11L, foodId = 1L),
                            ScanHistory.record(memberId = 11L, foodId = 2L),
                        ),
                    )

                    countHistories() shouldBe 2
                }
            }
        }

        given("최근 스캔 음식 조회") {
            `when`("같은 음식을 여러 번 스캔했으면") {
                then("가장 최근 1건만 남기고 최신순으로 반환한다") {
                    seedFood(1L, "김치찌개")
                    seedFood(2L, "비빔밥")
                    seedHistory(11L, 1L, "2026-07-01 10:00:00")
                    seedHistory(11L, 2L, "2026-07-02 10:00:00")
                    seedHistory(11L, 1L, "2026-07-03 10:00:00")

                    service.findRecentReadyFoodIds(memberId = 11L, limit = 10) shouldContainExactly listOf(1L, 2L)
                }
            }

            `when`("완성(READY)되지 않은 음식 이력이 섞여 있으면") {
                then("READY 음식만 반환한다") {
                    seedFood(1L, "김치찌개")
                    seedFood(2L, "미완성찌개", contentStatus = "INCOMPLETE")
                    seedHistory(11L, 2L, "2026-07-03 10:00:00")
                    seedHistory(11L, 1L, "2026-07-01 10:00:00")

                    service.findRecentReadyFoodIds(memberId = 11L, limit = 10) shouldContainExactly listOf(1L)
                }
            }

            `when`("이력이 limit 보다 많으면") {
                then("최신순 limit 개만 반환한다") {
                    (1L..12L).forEach { id ->
                        seedFood(id, "메뉴$id")
                        seedHistory(11L, id, "2026-07-01 10:00:${"%02d".format(id)}")
                    }

                    val result = service.findRecentReadyFoodIds(memberId = 11L, limit = 10)

                    result shouldContainExactly (12L downTo 3L).toList()
                }
            }

            `when`("다른 회원의 이력이 섞여 있으면") {
                then("요청한 회원의 이력만 반환한다") {
                    seedFood(1L, "김치찌개")
                    seedFood(2L, "비빔밥")
                    seedHistory(11L, 1L, "2026-07-01 10:00:00")
                    seedHistory(99L, 2L, "2026-07-02 10:00:00")

                    service.findRecentReadyFoodIds(memberId = 11L, limit = 10) shouldContainExactly listOf(1L)
                }
            }

            `when`("이력이 없으면") {
                then("빈 목록을 반환한다") {
                    service.findRecentReadyFoodIds(memberId = 11L, limit = 10) shouldBe emptyList()
                }
            }
        }
    }
}
