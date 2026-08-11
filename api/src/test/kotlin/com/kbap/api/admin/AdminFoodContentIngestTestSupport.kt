package com.kbap.api.admin

import com.kbap.common.domain.LanguageCode

object AdminFoodContentIngestTestSupport {
    const val PATH = "/api/admin/foods/contents"

    val targetLangs: List<String> = LanguageCode.entries.filter { it != LanguageCode.KO }.map { it.code }

    fun allTargets(value: String): Map<String, String> = targetLangs.associateWith { "$value-$it" }

    fun passedBody(
        foodId: Long,
        description: String = "들깨를 곱게 갈아 넣어 고소한 칼국수",
        spiciness: Int = 2,
        nameTranslations: Map<String, String> = allTargets("칼국수"),
        descriptionTranslations: Map<String, String> = allTargets("noodle"),
        ingredients: List<Map<String, Any>> = listOf(mapOf("code" to "SESAME", "inclusion_percent" to 100)),
    ): Map<String, Any?> = mapOf(
        "foodId" to foodId,
        "passed" to true,
        "description" to description,
        "spiciness" to spiciness,
        "nameTranslations" to nameTranslations,
        "descriptionTranslations" to descriptionTranslations,
        "ingredients" to ingredients,
    )

    fun failedBody(
        foodId: Long,
        failureKind: String = "JUDGE_REJECTED",
        reason: String = "번역 점수 78점으로 임계값 미달",
    ): Map<String, Any?> = mapOf(
        "foodId" to foodId,
        "passed" to false,
        "failureKind" to failureKind,
        "reason" to reason,
    )
}
