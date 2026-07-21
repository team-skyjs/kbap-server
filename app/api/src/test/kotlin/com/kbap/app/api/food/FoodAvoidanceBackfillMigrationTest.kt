package com.kbap.app.api.food

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.core.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.io.File
import javax.sql.DataSource

@SpringBootTest
@Import(MySqlContainerConfig::class)
class FoodAvoidanceBackfillMigrationTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val mapper = jacksonObjectMapper()

        fun backfillUpdateStatement(): String {
            val dirUrl = Thread.currentThread().contextClassLoader.getResource(MIGRATION_DIR)
            dirUrl.shouldNotBeNull()
            val file = File(dirUrl.toURI())
                .listFiles { f -> f.name.matches(BACKFILL_FILE_NAME) }
                ?.singleOrNull()
            file.shouldNotBeNull()
            // 세미콜론 split — 백필 SQL 문장 내부에 ';' 가 없다는 전제
            return file.readText().split(";")
                .map { it.trim() }
                .firstOrNull { it.matches(BACKFILL_UPDATE) }
                .shouldNotBeNull()
        }

        fun exec(statements: List<String>) {
            dataSource.connection.use { c ->
                c.createStatement().use { s -> statements.forEach { s.execute(it) } }
            }
        }

        fun seedScenario() {
            // substance_code FK 는 Flyway 마스터 카탈로그(81종, SOY·CLAM·WHEAT·EGG 포함)로 충족 — avoidance_substance 는 건드리지 않는다(공유 컨테이너 오염 방지)
            exec(
                listOf(
                    "DELETE FROM food_avoidance_substance",
                    "DELETE FROM bookmark",
                    "DELETE FROM scan_history",
                    "DELETE FROM food",
                ) + listOf(FOOD_TWO_ACTIVE, FOOD_ACTIVE_AND_DELETED, FOOD_NO_MAPPING).map { id ->
                    "INSERT INTO food (id, korean_name, description, spiciness, name_translations, " +
                        "description_translations, avoidance_substances, content_status, status, created_at, updated_at) " +
                        "VALUES ($id, '백필음식$id', '설명', 0, '{}', '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))"
                } + listOf(
                    fasRow(FOOD_TWO_ACTIVE, "SOY", 100, "ACTIVE"),
                    fasRow(FOOD_TWO_ACTIVE, "CLAM", 1, "ACTIVE"),
                    fasRow(FOOD_ACTIVE_AND_DELETED, "WHEAT", 50, "ACTIVE"),
                    fasRow(FOOD_ACTIVE_AND_DELETED, "EGG", 70, "DELETED"),
                ),
            )
        }

        fun avoidanceItemsOf(foodId: Long): List<Map<String, Any>> {
            val json = dataSource.connection.use { c ->
                c.prepareStatement("SELECT avoidance_substances FROM food WHERE id = ?").use { ps ->
                    ps.setLong(1, foodId)
                    ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
                }
            }
            return mapper.readValue(json)
        }

        fun fasRowCount(): Int =
            dataSource.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT COUNT(*) FROM food_avoidance_substance").use { rs -> rs.next(); rs.getInt(1) }
                }
            }

        fun deletedEggRemains(): Boolean =
            dataSource.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery(
                        "SELECT 1 FROM food_avoidance_substance " +
                            "WHERE food_id = $FOOD_ACTIVE_AND_DELETED AND substance_code = 'EGG' AND status = 'DELETED'",
                    ).use { it.next() }
                }
            }

        fun codePercentPairs(items: List<Map<String, Any>>): List<Pair<Any?, Any?>> =
            items.map { it["code"] to it["inclusion_percent"] }

        given("백필 마이그레이션 SQL") {
            `when`("db/migration 에서 백필 UPDATE 문을 찾으면") {
                then("파일명 패턴으로 유일하게 찾히고 UPDATE 문을 추출한다") {
                    backfillUpdateStatement().matches(BACKFILL_UPDATE) shouldBe true
                }
            }
        }

        given("구 매핑 테이블에 ACTIVE·DELETED·미매핑·경계값 행이 섞여 있다") {
            `when`("백필 UPDATE 를 실 MySQL 에서 실행하면") {
                then("각 음식의 avoidance_substances 가 ACTIVE 매핑 집합(code·확률)과 일치한다") {
                    seedScenario()
                    exec(listOf(backfillUpdateStatement()))

                    codePercentPairs(avoidanceItemsOf(FOOD_TWO_ACTIVE))
                        .shouldContainExactlyInAnyOrder("SOY" to 100, "CLAM" to 1)
                    codePercentPairs(avoidanceItemsOf(FOOD_ACTIVE_AND_DELETED))
                        .shouldContainExactlyInAnyOrder(listOf("WHEAT" to 50))
                }
            }

            `when`("DELETED 매핑을 가진 음식을 백필하면") {
                then("DELETED 행은 JSON 에서 제외된다") {
                    seedScenario()
                    exec(listOf(backfillUpdateStatement()))

                    avoidanceItemsOf(FOOD_ACTIVE_AND_DELETED).map { it["code"] } shouldBe listOf("WHEAT")
                }
            }

            `when`("매핑이 하나도 없는 음식을 백필하면") {
                then("빈 배열로 채워진다") {
                    seedScenario()
                    exec(listOf(backfillUpdateStatement()))

                    avoidanceItemsOf(FOOD_NO_MAPPING) shouldBe emptyList()
                }
            }

            `when`("경계 확률(1·100) 매핑을 백필하면") {
                then("확률 값이 손실 없이 보존된다") {
                    seedScenario()
                    exec(listOf(backfillUpdateStatement()))

                    avoidanceItemsOf(FOOD_TWO_ACTIVE).map { it["inclusion_percent"] }
                        .shouldContainExactlyInAnyOrder(1, 100)
                }
            }

            `when`("백필된 JSON 오브젝트의 키를 확인하면") {
                then("code·inclusion_percent 키만 담긴다(엔티티 역직렬화 계약과 일치)") {
                    seedScenario()
                    exec(listOf(backfillUpdateStatement()))

                    avoidanceItemsOf(FOOD_TWO_ACTIVE).forEach {
                        it.keys shouldContainExactlyInAnyOrder setOf("code", "inclusion_percent")
                    }
                }
            }

            `when`("백필 UPDATE 실행 후 원본 매핑 테이블을 확인하면") {
                then("행 수·DELETED 행이 그대로 보존된다(원본 무변화)") {
                    seedScenario()
                    val before = fasRowCount()
                    exec(listOf(backfillUpdateStatement()))

                    fasRowCount() shouldBe before
                    fasRowCount() shouldBe 4
                    deletedEggRemains() shouldBe true
                }
            }
        }
    }

    companion object {
        const val MIGRATION_DIR = "db/migration"
        val BACKFILL_FILE_NAME = Regex(".*add_food_avoidance_substances_json.*\\.sql")
        val BACKFILL_UPDATE = Regex("(?is)^update\\s+food\\b.*")

        const val FOOD_TWO_ACTIVE = 9100L
        const val FOOD_ACTIVE_AND_DELETED = 9101L
        const val FOOD_NO_MAPPING = 9102L

        private fun fasRow(foodId: Long, code: String, percent: Int, status: String) =
            "INSERT INTO food_avoidance_substance (food_id, substance_code, inclusion_percent, status, created_at, updated_at) " +
                "VALUES ($foodId, '$code', $percent, '$status', NOW(6), NOW(6))"
    }
}
