package com.kbap.domain.bookmark.dto

import com.kbap.domain.food.dto.FoodSummaryView

data class BookmarkPage(
    val items: List<FoodSummaryView>,
    val nextCursor: Long?,
    val hasNext: Boolean,
)
