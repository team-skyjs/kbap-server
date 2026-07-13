package com.kbap.application.food.usecase

import com.kbap.domain.food.FoodErrorCode
import com.kbap.domain.food.FoodException

fun resolveCursor(cursor: String?): Long? {
    if (cursor.isNullOrBlank()) return null
    return cursor.toLongOrNull()?.takeIf { it >= 0 } ?: throw FoodException(FoodErrorCode.INVALID_CURSOR)
}
