package com.meogo.application.client.food.usecase

import com.meogo.core.food.FoodErrorCode
import com.meogo.core.food.FoodException
import org.springframework.stereotype.Component

@Component
class CursorResolver {
    fun resolve(cursor: String?): Long? {
        if (cursor.isNullOrBlank()) return null
        val parsed = cursor.toLongOrNull() ?: throw FoodException(FoodErrorCode.INVALID_CURSOR)
        if (parsed < 0) throw FoodException(FoodErrorCode.INVALID_CURSOR)
        return parsed
    }
}
