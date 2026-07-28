package com.kbap.common.domain

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
        // 정확 일치만 인정 — trim·대소문자 보정을 하지 않는다(비어 있지 않은 값 보장은 요청 경계 책임).
        fun from(code: String): LanguageCode = entries.firstOrNull { it.code == code } ?: EN
    }
}
