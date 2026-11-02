package com.kbap.api.food

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

@IntegrationTest
class FoodRamyeonNameMigrationTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var dataSource: DataSource

    private val mapper = jacksonObjectMapper()
    private val migrationPath = "db/migration/V2026.11.02.15.01.04__food_name_ramyeon.sql"

    init {
        given("음식 이름 라면 표기 데이터 마이그레이션(KB-960)") {
            `when`("라면 음식의 라틴 로케일 이름에 Ramen 이 있으면") {
                then("en·es·vi·id 만 Ramyeon 으로 바꾸고, 라멘 음식과 다른 로케일은 그대로 둔다") {
                    TestTables.clearAll(dataSource)
                    val jdbc = JdbcTemplate(dataSource)
                    fun food(id: Long, korean: String, names: Map<String, String>) = jdbc.update(
                        "INSERT INTO food (id, korean_name, display_name, description, spiciness, name_translations, description_translations, ingredients, content_status, status, created_at, updated_at) VALUES (?, ?, ?, '설명', 0, ?, '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))",
                        id, korean, korean, mapper.writeValueAsString(names),
                    )
                    food(97001, "라면", mapOf("en" to "Korean Ramen", "es" to "Ramen coreano", "ja" to "ラーメン", "ru" to "Рамён"))
                    food(97002, "김치라면", mapOf("en" to "Kimchi Ramen", "vi" to "Mì Ramen kim chi", "id" to "Ramen Kimchi", "zh-Hans" to "泡菜拉面"))
                    food(97003, "미소라멘", mapOf("en" to "Miso Ramen", "es" to "Ramen de miso"))
                    food(97004, "라면사리", mapOf("en" to "Ramen Noodles"))

                    ClassPathResource(migrationPath).inputStream.bufferedReader(Charsets.UTF_8).readText()
                        .split(";").map { it.trim() }.filter { it.isNotEmpty() && !it.lines().all { line -> line.isBlank() || line.trim().startsWith("--") } }
                        .forEach { jdbc.execute(it) }

                    fun names(id: Long): Map<String, String> = mapper.readValue(
                        jdbc.queryForObject("SELECT CAST(name_translations AS CHAR) FROM food WHERE id = ?", String::class.java, id),
                        mapper.typeFactory.constructMapType(Map::class.java, String::class.java, String::class.java),
                    )
                    names(97001) shouldBe mapOf("en" to "Korean Ramyeon", "es" to "Ramyeon coreano", "ja" to "ラーメン", "ru" to "Рамён")
                    names(97002) shouldBe mapOf("en" to "Kimchi Ramyeon", "vi" to "Mì Ramyeon kim chi", "id" to "Ramyeon Kimchi", "zh-Hans" to "泡菜拉面")
                    names(97003) shouldBe mapOf("en" to "Miso Ramen", "es" to "Ramen de miso")
                    names(97004) shouldBe mapOf("en" to "Ramyeon Noodles")
                    TestTables.clearAll(dataSource)
                }
            }
        }
    }
}
