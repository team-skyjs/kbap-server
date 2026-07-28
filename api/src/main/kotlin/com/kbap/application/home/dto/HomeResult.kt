package com.kbap.application.home.dto

import com.kbap.common.domain.food.dto.FoodSummaryView

data class HomeResult(
    val avoidedSubstances: List<AvoidedSubstanceView>,
    val popularFoods: List<FoodSummaryView>,
    val recentScans: List<FoodSummaryView>,
)
