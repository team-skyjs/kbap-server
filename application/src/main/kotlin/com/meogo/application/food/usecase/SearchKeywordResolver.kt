package com.meogo.application.food.usecase

import com.meogo.domain.food.FoodErrorCode
import com.meogo.domain.food.FoodException

fun resolveKeyword(keyword: String?): String {
    val trimmed = keyword?.trim().orEmpty()
    if (trimmed.isEmpty()) throw FoodException(FoodErrorCode.BLANK_SEARCH_KEYWORD)
    return trimmed
}
