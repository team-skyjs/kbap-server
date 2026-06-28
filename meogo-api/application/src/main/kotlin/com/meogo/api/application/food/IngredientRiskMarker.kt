package com.meogo.api.application.food

import com.meogo.api.core.risk.RiskLevel
import com.meogo.api.food.Ingredient

interface IngredientRiskMarker {
    fun mark(ingredients: List<Ingredient>): List<RiskLevel>
}
