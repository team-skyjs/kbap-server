package com.kbap.api.home

import com.kbap.api.food.FoodSummaryView
import com.kbap.api.ingredient.AvoidedIngredientView

data class HomeResult(
    val avoidedSubstances: List<AvoidedIngredientView>,
    val popularFoods: List<FoodSummaryView>,
    val mostReviewedFoods: List<FoodSummaryView>,
    val recentScans: List<FoodSummaryView>,
)
