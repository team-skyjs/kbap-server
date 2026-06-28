package com.meogo.api.application.food

import com.meogo.api.core.risk.RiskLevel
import com.meogo.api.food.Ingredient
import org.springframework.stereotype.Component

@Component
class MockIngredientRiskMarker : IngredientRiskMarker {
    override fun mark(ingredients: List<Ingredient>): List<RiskLevel> =
        ingredients.mapIndexed { index, _ ->
            if (index == 0) RiskLevel.CAUTION else RiskLevel.SAFE
        }
}
