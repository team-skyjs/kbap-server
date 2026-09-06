package com.kbap.api.home

import com.kbap.api.food.FoodSummaryView
import java.time.Instant

data class HomeResult(
    val avoidedSubstances: List<AvoidedSubstanceView>,
    val popularFoods: List<FoodSummaryView>,
    val recentScans: List<RecentScanView>,
) {
    data class RecentScanView(
        val food: FoodSummaryView,
        val scannedAt: Instant,
    )
}
