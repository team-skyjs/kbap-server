package com.kbap.api.food

data class FoodSearchPage(
    val items: List<FoodSummaryView>,
    val nextCursor: FoodSearchCursor?,
    val hasNext: Boolean,
)
