package com.kbap.common.domain

import java.util.Locale

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

    val locale: Locale = Locale.forLanguageTag(code)

    companion object {
        fun from(code: String): LanguageCode = entries.firstOrNull { it.code == code } ?: EN
    }
}
