package com.meogo.api.food

import com.meogo.api.core.risk.RiskLevel
import com.meogo.api.core.stereotype.DomainService

@DomainService
class MockIngredientRiskMarker : IngredientRiskMarker {
    override fun mark(ingredients: List<Ingredient>): List<RiskLevel> =
        ingredients.mapIndexed { index, _ ->
            if (index == 0) RiskLevel.CAUTION else RiskLevel.SAFE
        }
}
