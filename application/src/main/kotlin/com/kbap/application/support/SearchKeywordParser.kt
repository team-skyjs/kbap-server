package com.kbap.application.support

import com.kbap.core.error.ErrorCode
import com.kbap.core.error.KbapException

object SearchKeywordParser {
    fun parse(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) throw KbapException(ErrorCode.BLANK_SEARCH_KEYWORD)
        return trimmed
    }
}
