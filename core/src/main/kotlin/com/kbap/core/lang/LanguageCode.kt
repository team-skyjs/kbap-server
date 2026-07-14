package com.kbap.core.lang

import com.kbap.core.error.ErrorCode
import com.kbap.core.error.BusinessException
enum class LanguageCode(val code: String) {
    KO("ko"),
    ZH_HANS("zh-Hans"),
    EN("en"),
    JA("ja"),
    ZH_HANT("zh-Hant"),
    VI("vi"),
    ID("id"),
    TH("th"),
    RU("ru"),
    ES("es"),
    ;

    companion object {
        fun from(code: String?): LanguageCode {
            val trimmed = code?.trim().orEmpty()
            if (trimmed.isEmpty()) {
                return KO
            }
            return entries.firstOrNull { it.code == trimmed }
                ?: throw BusinessException(ErrorCode.UNSUPPORTED_LANGUAGE)
        }
    }
}
