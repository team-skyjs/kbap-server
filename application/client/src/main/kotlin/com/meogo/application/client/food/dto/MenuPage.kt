package com.meogo.application.client.food.dto

data class MenuPage(
    val items: List<MenuSummaryView>,
    val nextCursor: Long?,
    val hasNext: Boolean,
)
