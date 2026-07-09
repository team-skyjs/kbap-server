package com.meogo.application.client.food.dto

data class FoodPage(
    val items: List<FoodSummaryView>,
    val nextCursor: Long?,
    val hasNext: Boolean,
)
