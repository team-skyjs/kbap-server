package com.kbap.application.food.usecase

import com.kbap.domain.food.FoodErrorCode
import com.kbap.domain.food.FoodException

fun resolveKeyword(keyword: String?): String {
    val trimmed = keyword?.trim().orEmpty()
    if (trimmed.isEmpty()) throw FoodException(FoodErrorCode.BLANK_SEARCH_KEYWORD)
    return trimmed
}
