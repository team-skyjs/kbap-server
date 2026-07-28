package com.kbap.api.home

import com.kbap.common.domain.food.dto.FoodSummaryView

data class HomeResult(
    val avoidedSubstances: List<AvoidedSubstanceView>,
    val popularFoods: List<FoodSummaryView>,
    val recentScans: List<FoodSummaryView>,
)
