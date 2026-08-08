package com.kbap.common.domain.food

import com.kbap.common.domain.food.model.Food
import org.springframework.jdbc.core.JdbcTemplate

class FoodRepositoryCustomImpl(
    private val jdbcTemplate: JdbcTemplate,
) : FoodRepositoryCustom {
    override fun upsertIncomplete(foods: List<Food>) {
        val rows = foods.joinToString(", ") { "(?, ?, ?, ?, '{}', '{}', NULL, ?, 'ACTIVE', NOW(6), NOW(6))" }
        val sql =
            """
            insert into food (korean_name, display_name, description, spiciness, name_translations, description_translations,
                              ingredients, content_status, status, created_at, updated_at)
            values $rows
            on duplicate key update
                display_name = if(food.display_name = '', values(display_name), food.display_name)
            """.trimIndent()
        val params: List<Any> = foods.flatMap {
            listOf(it.koreanName, it.displayName, it.description, it.spiciness, it.contentStatus.name)
        }
        jdbcTemplate.update(sql, *params.toTypedArray())
    }
}
