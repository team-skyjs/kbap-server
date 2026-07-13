package com.kbap.application.food.usecase

import com.kbap.core.error.ErrorCode
import com.kbap.core.error.KbapException

fun resolveCursor(cursor: String?): Long? {
    if (cursor.isNullOrBlank()) return null
    return cursor.toLongOrNull()?.takeIf { it >= 0 } ?: throw KbapException(ErrorCode.INVALID_CURSOR)
}
