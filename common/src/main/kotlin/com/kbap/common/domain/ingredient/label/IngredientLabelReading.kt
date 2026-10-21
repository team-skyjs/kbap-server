package com.kbap.common.domain.ingredient.label

import com.kbap.common.domain.ingredient.model.IngredientCode

data class IngredientLabelReading(
    val detections: List<IngredientLabelDetection>,
    val unmappedTokens: List<String>,
    val declaration: AllergenDeclaration,
) {
    fun matchOf(code: IngredientCode): IngredientLabelMatch =
        if (detections.any { it.code == code }) IngredientLabelMatch.DETECTED else IngredientLabelMatch.UNCONFIRMED
}

data class IngredientLabelDetection(
    val code: IngredientCode,
    val source: IngredientLabelSource,
    val evidence: String,
)

enum class IngredientLabelSource { ALLERGEN_DECLARATION, RAW_MATERIALS }

enum class IngredientLabelMatch { DETECTED, UNCONFIRMED }

enum class AllergenDeclaration { LISTED, NOT_PROVIDED }
