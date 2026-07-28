package com.kbap.api.common

import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.error.BusinessException

object CursorParser {
    fun parse(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return raw.toLongOrNull()?.takeIf { it >= 0 } ?: throw BusinessException(ErrorCode.INVALID_CURSOR)
    }
}
