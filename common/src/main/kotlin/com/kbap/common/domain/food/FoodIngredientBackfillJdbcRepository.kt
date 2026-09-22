package com.kbap.common.domain.food

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class FoodIngredientBackfillJdbcRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findAllSources(): List<FoodIngredientSource> =
        jdbcTemplate.query(
            "SELECT id, ingredients, ingredients_assessed FROM food ORDER BY id",
        ) { rs, _ -> FoodIngredientSource(rs.getLong(1), rs.getString(2), rs.getBoolean(3)) }

    fun findSource(foodId: Long): FoodIngredientSource? =
        jdbcTemplate.query(
            "SELECT id, ingredients, ingredients_assessed FROM food WHERE id = ?",
            { rs, _ -> FoodIngredientSource(rs.getLong(1), rs.getString(2), rs.getBoolean(3)) },
            foodId,
        ).firstOrNull()

    fun findSourceForUpdate(foodId: Long): FoodIngredientSource? =
        jdbcTemplate.query(
            "SELECT id, ingredients, ingredients_assessed FROM food WHERE id = ? FOR UPDATE",
            { rs, _ -> FoodIngredientSource(rs.getLong(1), rs.getString(2), rs.getBoolean(3)) },
            foodId,
        ).firstOrNull()

    fun findAllRelations(): Map<Long, List<FoodIngredientRelation>> =
        jdbcTemplate.query(
            """
            SELECT fi.food_id, i.code, fi.inclusion_percent
            FROM food_ingredient fi
            JOIN ingredients i ON i.id = fi.ingredient_id
            ORDER BY fi.food_id, fi.sort_order
            """.trimIndent(),
        ) { rs, _ -> rs.getLong(1) to FoodIngredientRelation(rs.getString(2), rs.getInt(3)) }
            .groupBy({ it.first }, { it.second })

    fun markAssessed(foodId: Long, assessed: Boolean) {
        jdbcTemplate.update("UPDATE food SET ingredients_assessed = ? WHERE id = ?", assessed, foodId)
    }
}

data class FoodIngredientSource(
    val foodId: Long,
    val rawIngredients: String?,
    val assessed: Boolean,
)

data class FoodIngredientRelation(
    val code: String,
    val inclusionPercent: Int,
)
