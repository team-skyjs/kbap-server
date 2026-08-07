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
class FoodDisplayNameBackfillTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        fun insertLegacyFood(koreanName: String) {
            dataSource.connection.use { c ->
                c.prepareStatement("DELETE FROM food WHERE korean_name = ?")
                    .use { ps -> ps.setString(1, koreanName); ps.executeUpdate() }
                c.prepareStatement(
                    """
                    INSERT INTO food (korean_name, description, spiciness, name_translations,
                                      description_translations, ingredient, content_status,
                                      status, created_at, updated_at)
                    VALUES (?, '설명', 0, '{}', '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))
                    """,
                ).use { ps -> ps.setString(1, koreanName); ps.executeUpdate() }
            }
        }

        fun displayNameOf(koreanName: String): String =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT display_name FROM food WHERE korean_name = ?").use { ps ->
                    ps.setString(1, koreanName)
                    ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
                }
            }

        fun blankDisplayNameCount(): Long =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT COUNT(*) FROM food WHERE display_name = ''").use { ps ->
                    ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                }
            }

        fun backfill(): Int =
            dataSource.connection.use { c ->
                c.prepareStatement("UPDATE food SET display_name = korean_name WHERE display_name = ''")
                    .use { ps -> ps.executeUpdate() }
            }

        fun normalizeKoreanNames(): Int =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    UPDATE food f
                    LEFT JOIN food dup
                        ON dup.korean_name = CONVERT(REGEXP_REPLACE(f.korean_name COLLATE utf8mb4_bin, '[^가-힣]', '') USING utf8mb4)
                       AND dup.id <> f.id
                    SET f.korean_name = CONVERT(REGEXP_REPLACE(f.korean_name COLLATE utf8mb4_bin, '[^가-힣]', '') USING utf8mb4)
                    WHERE dup.id IS NULL
                      AND CONVERT(REGEXP_REPLACE(f.korean_name COLLATE utf8mb4_bin, '[^가-힣]', '') USING utf8mb4) <> ''
                      AND f.korean_name <> CONVERT(REGEXP_REPLACE(f.korean_name COLLATE utf8mb4_bin, '[^가-힣]', '') USING utf8mb4)
                    """,
                ).use { ps -> ps.executeUpdate() }
            }

        fun namesOf(id: Long): Pair<String, String> =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT korean_name, display_name FROM food WHERE id = ?").use { ps ->
                    ps.setLong(1, id)
                    ps.executeQuery().use { rs -> rs.next(); rs.getString(1) to rs.getString(2) }
                }
            }

        fun idOf(koreanName: String): Long =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT id FROM food WHERE korean_name = ?").use { ps ->
                    ps.setString(1, koreanName)
                    ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                }
            }

        given("display_name 백필 — 마이그레이션 이전에 적재된 음식") {
            `when`("표시명 없이 적재된 행에 백필을 적용하면") {
                then("매칭용 이름과 같은 값으로 채워지고 빈 표시명이 남지 않는다") {
                    insertLegacyFood("백필비빔밥")
                    displayNameOf("백필비빔밥") shouldBe ""

                    backfill()

                    displayNameOf("백필비빔밥") shouldBe "백필비빔밥"
                    blankDisplayNameCount() shouldBe 0L
                }
            }
        }

        given("korean_name 정규화 마무리 — 이전 정규화에서 건너뛴 행") {
            `when`("정규화되지 않은 이름의 행에 백필 후 정규화를 적용하면") {
                then("표시명은 원본 표기를 지키고 매칭용 이름만 match key 가 된다") {
                    insertLegacyFood("잔여 들깨 칼국수")
                    val id = idOf("잔여 들깨 칼국수")

                    backfill()
                    normalizeKoreanNames()

                    namesOf(id) shouldBe ("잔여들깨칼국수" to "잔여 들깨 칼국수")
                }
            }

            `when`("정규화하면 기존 행과 충돌하는 행이면") {
                then("충돌 행은 건드리지 않는다(수동 병합 대상)") {
                    insertLegacyFood("충돌김치찌개")
                    insertLegacyFood("충돌 김치찌개")
                    val collidingId = idOf("충돌 김치찌개")

                    backfill()
                    normalizeKoreanNames()

                    namesOf(collidingId) shouldBe ("충돌 김치찌개" to "충돌 김치찌개")
                }
            }
        }
    }
}
