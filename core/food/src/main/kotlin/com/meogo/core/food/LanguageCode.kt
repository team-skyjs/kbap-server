package com.meogo.core.food

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
        fun from(code: String?): LanguageCode =
            entries.firstOrNull { it.code == code?.trim() } ?: KO
    }
}
