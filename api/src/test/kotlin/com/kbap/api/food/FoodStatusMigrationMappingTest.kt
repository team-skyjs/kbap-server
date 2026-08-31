package com.kbap.api.food

import com.kbap.api.IntegrationTest
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import javax.sql.DataSource

@IntegrationTest
class FoodStatusMigrationMappingTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        // 파일명(버전)에 결합되지 않게 glob 으로 찾는다 — 경로 하드코딩은 파일을 못 찾아도 빈 SQL 로 조용히 통과한다
        fun migrationSql(): String {
            val resources = PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/*__food_content_status_simplify.sql")
            check(resources.size == 1) { "상태 간소화 마이그레이션을 정확히 1개 찾아야 합니다: ${resources.size}" }
            return resources.single().inputStream.bufferedReader().readText()
        }

        fun execute(vararg statements: String) {
            dataSource.connection.use { c ->
                c.createStatement().use { s -> statements.forEach { sql -> s.execute(sql) } }
            }
        }

        fun runMigration() {
            val statements = migrationSql()
                .lineSequence()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
                .split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            execute(*statements.toTypedArray())
        }

        fun statusColumn(field: String): String? =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    SELECT $field FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'food' AND COLUMN_NAME = 'content_status'
                    """,
                ).use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getString(1) } }
            }

        fun insertLegacyFood(koreanName: String, legacyStatus: String) {
            dataSource.connection.use { c ->
                c.prepareStatement("DELETE FROM food WHERE korean_name = ?")
                    .use { ps -> ps.setString(1, koreanName); ps.executeUpdate() }
                c.prepareStatement(
                    """
                    INSERT INTO food (korean_name, display_name, description, spiciness, name_translations,
                                      description_translations, ingredients, content_status,
                                      status, created_at, updated_at)
                    VALUES (?, ?, '설명', 0, '{}', '{}', '[]', ?, 'ACTIVE', NOW(6), NOW(6))
                    """,
                ).use { ps ->
                    ps.setString(1, koreanName)
                    ps.setString(2, koreanName)
                    ps.setString(3, legacyStatus)
                    ps.executeUpdate()
                }
            }
        }

        fun statusOf(koreanName: String): String =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT content_status FROM food WHERE korean_name = ?").use { ps ->
                    ps.setString(1, koreanName)
                    ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
                }
            }

        fun deleteFoods(koreanNames: Collection<String>) {
            dataSource.connection.use { c ->
                c.prepareStatement("DELETE FROM food WHERE korean_name = ?").use { ps ->
                    koreanNames.forEach { ps.setString(1, it); ps.executeUpdate() }
                }
            }
        }

        given("food 상태 간소화 마이그레이션") {
            `when`("마이그레이션이 적용된 스키마를 보면") {
                then("content_status 는 신규 4값만 허용하고 기본값을 갖지 않는다") {
                    statusColumn("COLUMN_TYPE") shouldBe "enum('FAILED','PENDING_IMAGE','PENDING_REVIEW','READY')"
                    // 기본값이 있으면 상태를 빠뜨린 INSERT 가 조용히 특정 상태로 들어간다 — 상태는 항상 명시해야 한다
                    statusColumn("COLUMN_DEFAULT") shouldBe null
                }
            }

            `when`("구 6상태 행이 남아 있던 상황을 재현하고 마이그레이션을 재실행하면") {
                then("매핑 규칙대로 신 4상태로 이관되고 구 상태는 남지 않는다") {
                    val legacyByName = mapOf(
                        "마이그레이션READY" to "READY",
                        "마이그레이션이미지대기" to "PENDING_IMAGE",
                        "마이그레이션검수대기" to "PENDING_REVIEW",
                        "마이그레이션검수완료" to "REVIEWED",
                        "마이그레이션검수탈락" to "REVIEW_REJECTED",
                        "마이그레이션미완성" to "INCOMPLETE",
                    )
                    execute(
                        """
                        ALTER TABLE food MODIFY COLUMN content_status
                            ENUM('INCOMPLETE','PENDING_IMAGE','PENDING_REVIEW','REVIEWED','REVIEW_REJECTED','READY','FAILED')
                            NOT NULL
                        """,
                    )
                    legacyByName.forEach { (name, status) -> insertLegacyFood(name, status) }

                    runMigration()

                    statusOf("마이그레이션READY") shouldBe "READY"
                    statusOf("마이그레이션이미지대기") shouldBe "PENDING_IMAGE"
                    statusOf("마이그레이션검수대기") shouldBe "PENDING_REVIEW"
                    statusOf("마이그레이션검수완료") shouldBe "PENDING_REVIEW"
                    statusOf("마이그레이션검수탈락") shouldBe "FAILED"
                    statusOf("마이그레이션미완성") shouldBe "FAILED"
                    statusColumn("COLUMN_TYPE") shouldBe "enum('FAILED','PENDING_IMAGE','PENDING_REVIEW','READY')"

                    deleteFoods(legacyByName.keys)
                }
            }
        }
    }
}
