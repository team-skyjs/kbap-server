package com.kbap.api.home

import com.kbap.api.food.FoodSummaryView

data class HomeResult(
    val avoidedSubstances: List<AvoidedSubstanceView>,
    val popularFoods: List<FoodSummaryView>,
    val recentScans: List<FoodSummaryView>,
)
