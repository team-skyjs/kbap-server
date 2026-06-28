package com.meogo.api.food

import com.meogo.api.core.risk.RiskLevel

interface IngredientRiskMarker {
    fun mark(ingredients: List<Ingredient>): List<RiskLevel>
}
