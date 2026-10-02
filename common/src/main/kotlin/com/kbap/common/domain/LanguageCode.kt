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

        fun sourceCodeOf(tag: String?): String? {
            val subtags = tag?.trim()?.takeIf { it.length <= MAX_TAG_LENGTH && LANGUAGE_TAG.matches(it) }?.split('-') ?: return null
            val language = subtags.first().lowercase()
            val rest = subtags.drop(1).map { it.lowercase() }
            return when {
                language in NOT_A_LANGUAGE -> null
                language == CHINESE -> if (TRADITIONAL_SCRIPT in rest || (SIMPLIFIED_SCRIPT !in rest && rest.any { it in TRADITIONAL_REGIONS })) ZH_HANT.code else ZH_HANS.code
                else -> entries.firstOrNull { it.code == language }?.code ?: language
            }
        }

        private const val MAX_TAG_LENGTH = 35
        private val LANGUAGE_TAG = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*")
        private val NOT_A_LANGUAGE = setOf("und", "mul", "mis", "zxx")
        private const val CHINESE = "zh"
        private const val TRADITIONAL_SCRIPT = "hant"
        private const val SIMPLIFIED_SCRIPT = "hans"
        private val TRADITIONAL_REGIONS = setOf("tw", "hk", "mo")
    }
}
