package com.kbap.api.food

data class SelectedFoodsResponse(
    val items: List<FoodSummaryResponse>,
    val unavailableFoodIds: List<Long>,
)
