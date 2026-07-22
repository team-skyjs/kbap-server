package com.kbap.domain.food

import com.kbap.domain.food.model.Food
import org.springframework.jdbc.core.JdbcTemplate

class FoodJpaRepositoryCustomImpl(
    private val jdbcTemplate: JdbcTemplate,
) : FoodJpaRepositoryCustom {
    // insert-or-ignore: korean_name unique 충돌 시 no-op(동시 등록 idempotent). 바인딩은 행당 4개(placeholder 개수와 결합).
    override fun upsertIncomplete(foods: List<Food>) {
        val rows = foods.joinToString(", ") { "(?, ?, ?, '{}', '{}', '[]', ?, 'ACTIVE', NOW(6), NOW(6))" }
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
