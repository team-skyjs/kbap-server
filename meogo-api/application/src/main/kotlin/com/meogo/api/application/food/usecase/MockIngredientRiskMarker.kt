package com.meogo.api.application.food.usecase

import com.meogo.api.core.risk.RiskLevel
import com.meogo.api.food.Ingredient
import org.springframework.stereotype.Component

@Component
class MockIngredientRiskMarker {
    fun mark(ingredients: List<Ingredient>): Map<Long, RiskLevel> =
        ingredients.mapIndexedNotNull { index, ingredient ->
            ingredient.id?.let { id ->
                id to if (index == 0) RiskLevel.CAUTION else RiskLevel.SAFE
            }
        }.toMap()
}
