package com.meogo.application.client.food.usecase

import com.meogo.core.food.FoodErrorCode
import com.meogo.core.food.FoodException

fun resolveCursor(cursor: String?): Long? {
    if (cursor.isNullOrBlank()) return null
    return cursor.toLongOrNull()?.takeIf { it >= 0 } ?: throw FoodException(FoodErrorCode.INVALID_CURSOR)
}
