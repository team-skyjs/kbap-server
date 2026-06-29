package com.meogo.app.api.food

import javax.sql.DataSource

object FoodTestSeed {
    const val DOENJANG_BRIEF_KO = "구수한 한국식 된장찌개"
    const val DOENJANG_DETAILED_KO = "된장찌개는 된장을 풀어 끓인 한국의 대표적인 찌개다."
    const val DOENJANG_BRIEF_EN = "A hearty Korean soybean paste stew."
    const val DOENJANG_DETAILED_EN = "Doenjang-jjigae is a traditional Korean stew made with soybean paste."

    const val BIBIMBAP_BRIEF_KO = "비빔밥은 밥에 나물을 비벼 먹는 음식이다"
    const val BIBIMBAP_DETAILED_KO = "비빔밥은 밥 위에 여러 나물과 고추장을 올려 비벼 먹는 한국 음식이다."
    const val BIBIMBAP_DETAILED_EN = "Bibimbap is a Korean rice dish topped with vegetables and gochujang."

    fun seedDoenjangStew(dataSource: DataSource) {
        execute(
            dataSource,
            clearStatements() + listOf(
                food(1, "된장찌개", "doenjang.png", DOENJANG_BRIEF_KO, DOENJANG_DETAILED_KO),
                foodName(1, "en", "Doenjang Stew"),
                foodName(1, "ja", "テンジャンチゲ"),
                foodDescription(1, "BRIEF", "en", DOENJANG_BRIEF_EN),
                foodDescription(1, "DETAILED", "en", DOENJANG_DETAILED_EN),
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

    fun seedPartialDescriptionFood(dataSource: DataSource) {
        execute(
            dataSource,
            listOf(
                food(2, "비빔밥", null, BIBIMBAP_BRIEF_KO, BIBIMBAP_DETAILED_KO),
                foodName(2, "en", "Bibimbap"),
                foodDescription(2, "DETAILED", "en", BIBIMBAP_DETAILED_EN),
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
        "DELETE FROM food_description_translation",
        "DELETE FROM ingredient",
        "DELETE FROM food",
    )

    private fun food(id: Long, koreanName: String, imageRef: String?, brief: String, detailed: String) =
        "INSERT INTO food (id, korean_name, image_ref, brief_description, detailed_description, status, created_at, updated_at) " +
            "VALUES ($id, '$koreanName', ${imageRef?.let { "'$it'" } ?: "NULL"}, '$brief', '$detailed', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"

    private fun foodName(foodId: Long, lang: String, name: String) =
        "INSERT INTO food_name_translation (food_id, lang_code, name, status, created_at, updated_at) " +
            "VALUES ($foodId, '$lang', '$name', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"

    private fun foodDescription(foodId: Long, kind: String, lang: String, content: String) =
        "INSERT INTO food_description_translation (food_id, kind, lang_code, content, status, created_at, updated_at) " +
            "VALUES ($foodId, '$kind', '$lang', '$content', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"

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
