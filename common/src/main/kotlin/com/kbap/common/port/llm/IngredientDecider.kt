package com.kbap.common.port.llm

import com.kbap.common.domain.ingredient.model.IngredientCode

fun interface IngredientDecider {
    fun decide(food: IngredientDecisionFood, codes: List<IngredientCode>): IngredientDecisions
}

data class IngredientDecisionFood(
    val koreanName: String,
    val description: String,
    val longDescription: String?,
    val currentCodes: List<String>,
)

data class IngredientPresence(val contains: Boolean, val probability: Double, val reason: String)

data class IngredientDecisions(
    val byCode: Map<IngredientCode, IngredientPresence>,
    val costKrw: Double,
)
