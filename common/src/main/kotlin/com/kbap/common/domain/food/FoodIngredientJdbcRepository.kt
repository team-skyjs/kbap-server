package com.kbap.common.domain.food

import com.kbap.common.domain.food.model.FoodIngredient
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

    private companion object {
        const val SORT_ORDER_STEP = 10
    }
}
