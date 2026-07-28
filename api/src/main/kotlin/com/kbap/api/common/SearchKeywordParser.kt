package com.kbap.api.common

import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.error.BusinessException

object SearchKeywordParser {
    fun parse(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) throw BusinessException(ErrorCode.BLANK_SEARCH_KEYWORD)
        return trimmed
    }
}
