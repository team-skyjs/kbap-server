package com.meogo.core.kernel.lang

import com.meogo.core.kernel.error.ErrorCode

enum class LanguageErrorCode(
    override val status: Int,
    override val message: String,
) : ErrorCode {
    UNSUPPORTED_LANGUAGE(
        400,
        "지원하지 않는 언어 코드입니다. 지원 언어: " + LanguageCode.entries.joinToString(", ") { it.code },
    ),
}
