package com.meogo.application.client.food.usecase

import com.meogo.core.kernel.risk.RiskLevel
import com.meogo.core.food.Ingredient
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
