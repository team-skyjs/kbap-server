package com.kbap.api.home

import javax.sql.DataSource

object HomeTestSeed {
    fun reset(dataSource: DataSource) = execute(
        dataSource,
        listOf(
            "DELETE FROM member_ranking_event",
            "DELETE FROM food_review",
            "DELETE FROM scan_history",
            "DELETE FROM avoidance_substance",
            "DELETE FROM food",
            "DELETE FROM member",
        ),
    )

    fun seedReadyFoods(dataSource: DataSource, count: Int) = execute(
        dataSource,
        (1..count).map { id ->
            "INSERT INTO food (id, korean_name, image_ref, description, spiciness, name_translations, " +
                "description_translations, avoidance_substances, content_status, status, created_at, updated_at) " +
                "VALUES ($id, '메뉴$id', 'menu-$id.png', '메뉴$id 설명', 0, " +
                """'{"en":"Menu$id","ja":"メニュー$id"}', '{"en":"Menu$id desc"}', '[]', """ +
                "'READY', 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))"
        },
    )

    fun seedSubstanceCatalog(dataSource: DataSource, vararg codes: String) = execute(
        dataSource,
        codes.mapIndexed { index, code ->
            "INSERT IGNORE INTO avoidance_substance (id, code, korean_name, translations, status, created_at, updated_at) " +
                "VALUES (${900 + index}, '$code', '${koreanNameOf(code)}', '${translationsOf(code)}', " +
                "'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))"
        },
    )

    fun seedFoodSubstance(dataSource: DataSource, foodId: Long, code: String, percent: Int) {
        seedSubstanceCatalog(dataSource, code)
        execute(
            dataSource,
            listOf(
                "UPDATE food SET avoidance_substances = JSON_ARRAY_APPEND(avoidance_substances, '$', " +
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
                "INSERT INTO member (id, provider, provider_uid, email, nickname, profile, member_status, " +
                    "onboarding_completed, status, created_at, updated_at) " +
                    "VALUES ($memberId, 'GOOGLE', 'home-test-$memberId', NULL, '홈테스터', " +
                    """'{"avoidanceSubstanceCodes":$codesJson,"spicinessPreference":"MEDIUM",""" +
                    """"countryCode":"US","appLanguage":"en"}', """ +
                    "'ACTIVE', 1, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
            ),
        )
    }

    fun seedScan(dataSource: DataSource, memberId: Long, foodId: Long, scannedAt: String) = execute(
        dataSource,
        listOf(
            "INSERT INTO scan_history (member_id, image_path, menu_name, korean_name, food_id, " +
                "status, created_at, updated_at) " +
                "VALUES ($memberId, 'scan/$memberId/x.jpg', '메뉴', '메뉴', $foodId, " +
                "'ACTIVE', '$scannedAt', '$scannedAt')",
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
