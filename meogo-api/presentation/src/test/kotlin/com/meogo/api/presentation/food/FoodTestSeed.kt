package com.meogo.api.presentation.food

import javax.sql.DataSource

object FoodTestSeed {
    fun seedDoenjangStew(dataSource: DataSource) {
        execute(
            dataSource,
            clearStatements() + listOf(
                "INSERT INTO food (id, korean_name, image_ref, status, created_at, updated_at) " +
                    "VALUES (1, '된장찌개', 'doenjang.png', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                foodName(1, "en", "Doenjang Stew"),
                foodName(1, "ja", "テンジャンチゲ"),
                ingredient(1, "된장", null),
                ingredient(2, "두부", null),
                ingredient(3, "바지락 조개", "clam.png"),
                ingredientName(1, "en", "Soybean paste"),
                ingredientName(2, "en", "Tofu"),
                ingredientName(3, "en", "Manila clam"),
                foodIngredient(1, 1, 100),
                foodIngredient(1, 2, 90),
                foodIngredient(1, 3, 50),
            ),
        )
    }

    fun clear(dataSource: DataSource) = execute(dataSource, clearStatements())

    private fun execute(dataSource: DataSource, statements: List<String>) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statements.forEach { statement.execute(it) }
            }
        }
    }

    private fun clearStatements() = listOf(
        "DELETE FROM food_ingredient",
        "DELETE FROM ingredient_name_translation",
        "DELETE FROM food_name_translation",
        "DELETE FROM ingredient",
        "DELETE FROM food",
    )

    private fun foodName(foodId: Long, lang: String, name: String) =
        "INSERT INTO food_name_translation (food_id, lang_code, name, status, created_at, updated_at) " +
            "VALUES ($foodId, '$lang', '$name', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"

    private fun ingredient(id: Long, koreanName: String, iconRef: String?) =
        "INSERT INTO ingredient (id, korean_name, icon_ref, status, created_at, updated_at) " +
            "VALUES ($id, '$koreanName', ${iconRef?.let { "'$it'" } ?: "NULL"}, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"

    private fun ingredientName(ingredientId: Long, lang: String, name: String) =
        "INSERT INTO ingredient_name_translation (ingredient_id, lang_code, name, status, created_at, updated_at) " +
            "VALUES ($ingredientId, '$lang', '$name', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"

    private fun foodIngredient(foodId: Long, ingredientId: Long, percent: Int) =
        "INSERT INTO food_ingredient (food_id, ingredient_id, inclusion_percent, status, created_at, updated_at) " +
            "VALUES ($foodId, $ingredientId, $percent, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
}
