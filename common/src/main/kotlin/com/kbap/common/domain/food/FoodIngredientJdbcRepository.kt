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
            .mapIndexed { index, ingredient -> ingredient to (index + 1) * SORT_ORDER_STEP }
            .filter { (ingredient, _) -> ingredient.inclusionPercent in STORABLE_PERCENT }
        if (rows.isEmpty()) return
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO food_ingredient (food_id, ingredient_id, inclusion_percent, sort_order)
            SELECT ?, id, ?, ? FROM ingredients WHERE code = ?
            """.trimIndent(),
            rows.map { (ingredient, sortOrder) -> arrayOf<Any>(foodId, ingredient.inclusionPercent, sortOrder, ingredient.code) },
        )
    }

    private companion object {
        const val SORT_ORDER_STEP = 10
        val STORABLE_PERCENT = 1..100
    }
}
