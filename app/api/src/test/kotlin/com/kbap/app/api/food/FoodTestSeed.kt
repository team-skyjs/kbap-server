package com.kbap.app.api.food

import javax.sql.DataSource

object FoodTestSeed {
    const val DOENJANG_DESCRIPTION_KO = "구수한 한국식 된장찌개"
    const val DOENJANG_DESCRIPTION_EN = "A hearty Korean soybean paste stew."
    const val DOENJANG_SPICINESS = 3

    const val BIBIMBAP_DESCRIPTION_KO = "비빔밥은 밥에 나물을 비벼 먹는 음식이다"
    const val BIBIMBAP_SPICINESS = 2

    const val PLAIN_RICE_DESCRIPTION_KO = "흰밥은 쌀로 지은 밥이다"
    const val PLAIN_RICE_SPICINESS = 0

    fun seedDoenjangStew(dataSource: DataSource) {
        execute(
            dataSource,
            clearStatements() + listOf(
                food(
                    1,
                    "된장찌개",
                    "doenjang.png",
                    DOENJANG_DESCRIPTION_KO,
                    DOENJANG_SPICINESS,
                    nameTranslations = mapOf("en" to "Doenjang Stew", "ja" to "テンジャンチゲ"),
                    descriptionTranslations = mapOf("en" to DOENJANG_DESCRIPTION_EN),
                    avoidanceSubstances = listOf("CLAM" to 50, "SOY" to 100, "WHEAT" to 80),
                ),
                avoidanceSubstance(101, "SOY", "대두", """{"en":"Soybean"}"""),
                avoidanceSubstance(102, "WHEAT", "밀", """{"en":"Wheat"}"""),
                avoidanceSubstance(103, "CLAM", "조개", """{"en":"Clam"}"""),
            ),
        )
    }

    fun seedPartialDescriptionFood(dataSource: DataSource) {
        execute(
            dataSource,
            listOf(
                food(
                    2,
                    "비빔밥",
                    null,
                    BIBIMBAP_DESCRIPTION_KO,
                    BIBIMBAP_SPICINESS,
                    nameTranslations = mapOf("en" to "Bibimbap"),
                ),
            ),
        )
    }

    fun seedPlainRice(dataSource: DataSource) {
        execute(
            dataSource,
            clearStatements() + listOf(
                food(3, "흰밥", null, PLAIN_RICE_DESCRIPTION_KO, PLAIN_RICE_SPICINESS),
            ),
        )
    }

    const val DELETED_FOOD_ID = 4L

    fun seedDeletedFood(dataSource: DataSource) {
        execute(
            dataSource,
            listOf(
                food(DELETED_FOOD_ID, "삭제된음식", null, "삭제된 음식 설명", 0, status = "DELETED"),
            ),
        )
    }


    fun seedMemberAvoiding(dataSource: DataSource, memberId: Long, vararg codes: String) {
        val codesJson = codes.joinToString(separator = ",", prefix = "[", postfix = "]") { "\"$it\"" }
        execute(
            dataSource,
            listOf(
                "DELETE FROM member WHERE id = $memberId",
                "INSERT INTO member (id, provider, provider_uid, email, nickname, profile, member_status, onboarding_completed, status, created_at, updated_at) " +
                    "VALUES ($memberId, 'GOOGLE', 'food-test-$memberId', NULL, '테스터$memberId', " +
                    "'{\"avoidanceSubstanceCodes\":$codesJson,\"spicinessPreference\":5,\"countryCode\":\"US\"}', " +
                    "'ACTIVE', 1, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
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
        "DELETE FROM scan_history",
        "DELETE FROM avoidance_substance",
        "DELETE FROM food",
    )

    private fun food(
        id: Long,
        koreanName: String,
        imageRef: String?,
        description: String,
        spiciness: Int,
        nameTranslations: Map<String, String> = emptyMap(),
        descriptionTranslations: Map<String, String> = emptyMap(),
        avoidanceSubstances: List<Pair<String, Int>> = emptyList(),
        status: String = "ACTIVE",
    ) =
        "INSERT INTO food (id, korean_name, image_ref, description, spiciness, name_translations, description_translations, avoidance_substances, status, created_at, updated_at) " +
            "VALUES ($id, '$koreanName', ${imageRef?.let { "'$it'" } ?: "NULL"}, '$description', $spiciness, " +
            "'${jsonObject(nameTranslations)}', '${jsonObject(descriptionTranslations)}', '${jsonArray(avoidanceSubstances)}', " +
            "'$status', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"

    private fun jsonObject(entries: Map<String, String>) =
        entries.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
            "\"$key\":\"$value\""
        }

    private fun jsonArray(substances: List<Pair<String, Int>>) =
        substances.joinToString(separator = ",", prefix = "[", postfix = "]") { (code, percent) ->
            """{"code":"$code","inclusion_percent":$percent}"""
        }

    private fun avoidanceSubstance(id: Long, code: String, koreanName: String, translationsJson: String) =
        "INSERT INTO avoidance_substance (id, code, korean_name, translations, status, created_at, updated_at) " +
            "VALUES ($id, '$code', '$koreanName', '$translationsJson', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
}
