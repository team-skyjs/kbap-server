package com.meogo.application.food.usecase

import com.meogo.domain.food.FoodErrorCode
import com.meogo.domain.food.FoodException

fun resolveCursor(cursor: String?): Long? {
    if (cursor.isNullOrBlank()) return null
    return cursor.toLongOrNull()?.takeIf { it >= 0 } ?: throw FoodException(FoodErrorCode.INVALID_CURSOR)
}
