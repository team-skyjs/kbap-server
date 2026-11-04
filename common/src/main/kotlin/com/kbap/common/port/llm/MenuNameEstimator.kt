package com.kbap.common.port.llm

import com.kbap.common.domain.ingredient.model.IngredientCode

fun interface MenuNameEstimator {
    fun estimate(koreanNames: List<String>): List<MenuNameEstimation>
}

enum class MenuNameKind { DISH, DRINK, NOT_FOOD }

data class MenuNameEstimation(
    val koreanName: String,
    val kind: MenuNameKind,
    val englishName: String?,
    val description: String?,
    val avoidanceCodes: List<IngredientCode>,
    val spiciness: Int,
    val confidence: Double?,
)
