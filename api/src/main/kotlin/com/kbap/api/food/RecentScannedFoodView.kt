package com.kbap.api.food

import java.time.Instant

data class RecentScannedFoodView(
    val summary: FoodSummaryView,
    val scannedAt: Instant,
)
