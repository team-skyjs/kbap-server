package com.kbap.app.api.common

import com.kbap.core.error.ErrorCode
import com.kbap.core.error.BusinessException

object CursorParser {
    fun parse(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return raw.toLongOrNull()?.takeIf { it >= 0 } ?: throw BusinessException(ErrorCode.INVALID_CURSOR)
    }
}
