package com.kbap.api.home

import javax.sql.DataSource

object HomeTestSeed {
    fun reset(dataSource: DataSource) = execute(
        dataSource,
        listOf(
            "DELETE FROM member_block",
            "DELETE FROM member_ranking_event",
            "DELETE FROM food_review",
            "DELETE FROM community_comment WHERE parent_id IS NOT NULL",
            "DELETE FROM community_comment",
            "DELETE FROM community_post",
            "DELETE FROM uploaded_image",
            "DELETE FROM scan_history",
            "DELETE FROM ingredients",
            "DELETE FROM food_content_outbox",
        "DELETE FROM food_vector_outbox",
        "DELETE FROM food",
            "DELETE FROM member",
        ),
    )

    fun seedReadyFoods(dataSource: DataSource, count: Int) = execute(
        dataSource,
        (1..count).map { id ->
            "INSERT INTO food (id, korean_name, image_ref, description, spiciness, name_translations, " +
                "description_translations, ingredients, content_status, status, created_at, updated_at) " +
                "VALUES ($id, '메뉴$id', 'menu-$id.png', '메뉴$id 설명', 0, " +
                """'{"en":"Menu$id","ja":"メニュー$id"}', '{"en":"Menu$id desc"}', '[]', """ +
                "'READY', 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))"
        },
    )

    fun seedSubstanceCatalog(dataSource: DataSource, vararg codes: String) = execute(
        dataSource,
        codes.mapIndexed { index, code ->
            "INSERT IGNORE INTO ingredients (id, code, korean_name, translations, status, created_at, updated_at) " +
                "VALUES (${900 + index}, '$code', '${koreanNameOf(code)}', '${translationsOf(code)}', " +
                "'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))"
        },
    )

    fun seedFoodSubstance(dataSource: DataSource, foodId: Long, code: String, percent: Int) {
        seedSubstanceCatalog(dataSource, code)
        execute(
            dataSource,
            listOf(
                "UPDATE food SET ingredients = JSON_ARRAY_APPEND(ingredients, '$', " +
                    "JSON_OBJECT('code', '$code', 'inclusion_percent', $percent)) WHERE id = $foodId",
            ),
        )
    }

    fun seedMember(dataSource: DataSource, memberId: Long, codes: List<String>) {
        val codesJson = codes.joinToString(separator = ",", prefix = "[", postfix = "]") { "\"$it\"" }
        seedSubstanceCatalog(dataSource, *codes.toTypedArray())
        execute(
            dataSource,
            listOf(
                "INSERT INTO member (id, provider, provider_uid, email, nickname, avoidance_substance_codes, spiciness_preference, country_code, member_status, " +
                    "onboarding_completed, status, created_at, updated_at) " +
                    "VALUES ($memberId, 'GOOGLE', 'home-test-$memberId', NULL, '홈테스터', '$codesJson', 'MEDIUM', 'US', " +
                    "'ACTIVE', 1, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
            ),
        )
    }

    fun seedScan(dataSource: DataSource, memberId: Long, foodId: Long, scannedAt: String) = execute(
        dataSource,
        listOf(
            "INSERT INTO scan_history (member_id, food_id, status, created_at, updated_at) " +
                "VALUES ($memberId, $foodId, 'ACTIVE', '$scannedAt', '$scannedAt')",
        ),
    )

    private fun koreanNameOf(code: String) = when (code) {
        "EGG" -> "계란"
        "MILK" -> "우유"
        else -> code
    }

    private fun translationsOf(code: String) = when (code) {
        "EGG" -> """{"en":"Egg","ja":"卵"}"""
        "MILK" -> """{"en":"Milk","ja":"牛乳"}"""
        else -> "{}"
    }

    private fun execute(dataSource: DataSource, statements: List<String>) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statements.forEach { statement.execute(it) }
            }
        }
    }
}
