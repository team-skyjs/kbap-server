package com.kbap.api.processedfood

import com.kbap.common.domain.ingredient.model.IngredientCode

object ProcessedFoodAvoidance {
    val EXTRA_IMPLICATIONS: Map<IngredientCode, Set<IngredientCode>> = emptyMap()

    fun coveredBy(chosen: IngredientCode): Set<IngredientCode> =
        setOf(chosen) + chosen.impliedCodes + EXTRA_IMPLICATIONS[chosen].orEmpty()
}
