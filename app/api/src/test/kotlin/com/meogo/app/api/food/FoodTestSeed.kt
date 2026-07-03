package com.meogo.app.api.food

import javax.sql.DataSource

object FoodTestSeed {
    const val DOENJANG_DESCRIPTION_KO = "구수한 한국식 된장찌개"
    const val DOENJANG_DESCRIPTION_EN = "A hearty Korean soybean paste stew."
    const val DOENJANG_SPICINESS = 3

    const val BIBIMBAP_DESCRIPTION_KO = "비빔밥은 밥에 나물을 비벼 먹는 음식이다"
    const val BIBIMBAP_SPICINESS = 2

    fun seedDoenjangStew(dataSource: DataSource) {
        execute(
            dataSource,
            clearStatements() + listOf(
                food(1, "된장찌개", "doenjang.png", DOENJANG_DESCRIPTION_KO, DOENJANG_SPICINESS),
                foodName(1, "en", "Doenjang Stew"),
                foodName(1, "ja", "テンジャンチゲ"),
                foodDescription(1, "en", DOENJANG_DESCRIPTION_EN),
                avoidanceSubstance(101, "SOY", "대두", """{"en":"Soybean"}"""),
                avoidanceSubstance(102, "WHEAT", "밀", """{"en":"Wheat"}"""),
                avoidanceSubstance(103, "CLAM", "조개", """{"en":"Clam"}"""),
                foodAvoidanceSubstance(1, "SOY", 100),
                foodAvoidanceSubstance(1, "WHEAT", 80),
                foodAvoidanceSubstance(1, "CLAM", 50),
            ),
        )
    }

    fun seedPartialDescriptionFood(dataSource: DataSource) {
        execute(
            dataSource,
            listOf(
                food(2, "비빔밥", null, BIBIMBAP_DESCRIPTION_KO, BIBIMBAP_SPICINESS),
                foodName(2, "en", "Bibimbap"),
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
        "DELETE FROM food_avoidance_substance",
        "DELETE FROM food_name_translation",
        "DELETE FROM food_description_translation",
        "DELETE FROM avoidance_substance",
        "DELETE FROM food",
    )

    private fun food(id: Long, koreanName: String, imageRef: String?, description: String, spiciness: Int) =
        "INSERT INTO food (id, korean_name, image_ref, description, spiciness, status, created_at, updated_at) " +
            "VALUES ($id, '$koreanName', ${imageRef?.let { "'$it'" } ?: "NULL"}, '$description', $spiciness, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"

    private fun foodName(foodId: Long, lang: String, name: String) =
        "INSERT INTO food_name_translation (food_id, lang_code, name, status, created_at, updated_at) " +
            "VALUES ($foodId, '$lang', '$name', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"

    private fun foodDescription(foodId: Long, lang: String, content: String) =
        "INSERT INTO food_description_translation (food_id, lang_code, content, status, created_at, updated_at) " +
            "VALUES ($foodId, '$lang', '$content', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"

    private fun avoidanceSubstance(id: Long, code: String, koreanName: String, translationsJson: String) =
        "INSERT INTO avoidance_substance (id, code, korean_name, translations, status, created_at, updated_at) " +
            "VALUES ($id, '$code', '$koreanName', '$translationsJson' FORMAT JSON, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"

    private fun foodAvoidanceSubstance(foodId: Long, substanceCode: String, percent: Int) =
        "INSERT INTO food_avoidance_substance (food_id, substance_code, inclusion_percent, status, created_at, updated_at) " +
            "VALUES ($foodId, '$substanceCode', $percent, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
}
