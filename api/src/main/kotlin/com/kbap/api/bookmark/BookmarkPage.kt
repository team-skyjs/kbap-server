package com.kbap.api.bookmark

import com.kbap.api.food.FoodSummaryView

data class BookmarkPage(
    val items: List<FoodSummaryView>,
    val nextCursor: Long?,
    val hasNext: Boolean,
)
