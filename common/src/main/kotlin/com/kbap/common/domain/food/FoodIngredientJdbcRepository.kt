package com.kbap.common.domain.food

import com.kbap.common.domain.food.model.FoodIngredient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class FoodIngredientJdbcRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun replace(foodId: Long, ingredients: List<FoodIngredient>?) {
        jdbcTemplate.update("DELETE FROM food_ingredient WHERE food_id = ?", foodId)
        val rows = ingredients.orEmpty()
        if (rows.isEmpty()) return
        val inserted = jdbcTemplate.batchUpdate(
            """
            INSERT INTO food_ingredient (food_id, ingredient_id, inclusion_percent, sort_order)
            SELECT ?, id, ?, ? FROM ingredients WHERE code = ?
            """.trimIndent(),
            rows.mapIndexed { index, it ->
                arrayOf<Any>(foodId, it.inclusionPercent, (index + 1) * SORT_ORDER_STEP, it.code)
            },
        ).sum()
        check(inserted == rows.size) { "food_ingredient 행 수 불일치: foodId=$foodId, 요청=${rows.size}, 저장=$inserted" }
    }

    fun findByFoodIds(foodIds: Collection<Long>): Map<Long, List<FoodIngredient>> {
        if (foodIds.isEmpty()) return emptyMap()
        val placeholders = foodIds.joinToString(",") { "?" }
        val rows = jdbcTemplate.query(
            """
            SELECT fi.food_id, i.code, fi.inclusion_percent
            FROM food_ingredient fi
            JOIN ingredients i ON i.id = fi.ingredient_id
            WHERE fi.food_id IN ($placeholders)
            ORDER BY fi.food_id, fi.sort_order
            """.trimIndent(),
            { rs, _ -> rs.getLong(1) to FoodIngredient(rs.getString(2), rs.getInt(3)) },
            *foodIds.toTypedArray(),
        )
        return rows.groupBy({ it.first }, { it.second }).mapValues { (foodId, ingredients) ->
            if (ingredients.size > MAX_READ_ROWS) {
                log.warn("음식 재료 관계가 조회 상한을 넘었다 — foodId={}, rows={}", foodId, ingredients.size)
            }
            ingredients.take(MAX_READ_ROWS)
        }
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(FoodIngredientJdbcRepository::class.java)

        const val SORT_ORDER_STEP = 10

        const val MAX_READ_ROWS = 21
    }
}
