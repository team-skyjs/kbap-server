package com.kbap.common.domain.food

import com.kbap.common.domain.food.model.Food
import org.springframework.jdbc.core.JdbcTemplate

class FoodJpaRepositoryCustomImpl(
    private val jdbcTemplate: JdbcTemplate,
) : FoodJpaRepositoryCustom {
    override fun upsertIncomplete(foods: List<Food>) {
        val rows = foods.joinToString(", ") { "(?, ?, ?, '{}', '{}', NULL, ?, 'ACTIVE', NOW(6), NOW(6))" }
        val sql =
            """
            insert into food (korean_name, description, spiciness, name_translations, description_translations,
                              avoidance_substances, content_status, status, created_at, updated_at)
            values $rows
            on duplicate key update id = id
            """.trimIndent()
        val params: List<Any> = foods.flatMap { listOf(it.koreanName, it.description, it.spiciness, it.contentStatus.name) }
        jdbcTemplate.update(sql, *params.toTypedArray())
    }
}
