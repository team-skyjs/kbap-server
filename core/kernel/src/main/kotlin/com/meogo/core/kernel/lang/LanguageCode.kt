package com.meogo.core.kernel.lang

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
                ?: throw LanguageException(LanguageErrorCode.UNSUPPORTED_LANGUAGE)
        }
    }
}
