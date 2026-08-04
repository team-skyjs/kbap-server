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
                                      description_translations, avoidance_substances, content_status,
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
    }
}
