package com.meogo.application.client.food.usecase

import com.meogo.core.food.FoodErrorCode
import com.meogo.core.food.FoodException

fun resolveKeyword(keyword: String?): String {
    val trimmed = keyword?.trim().orEmpty()
    if (trimmed.isEmpty()) throw FoodException(FoodErrorCode.BLANK_SEARCH_KEYWORD)
    return trimmed
}
