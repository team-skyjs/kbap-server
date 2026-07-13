package com.kbap.application.food

import com.kbap.core.error.ErrorCode
import com.kbap.core.error.KbapException

fun resolveKeyword(keyword: String?): String {
    val trimmed = keyword?.trim().orEmpty()
    if (trimmed.isEmpty()) throw KbapException(ErrorCode.BLANK_SEARCH_KEYWORD)
    return trimmed
}
