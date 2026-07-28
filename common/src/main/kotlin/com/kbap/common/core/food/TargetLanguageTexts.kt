package com.kbap.common.core.food

import com.kbap.common.core.lang.LanguageCode

class TargetLanguageTexts(
    texts: Map<LanguageCode, String>,
) {
    // 검증 후 스냅샷을 보관해 원본 맵 변형이 불변식을 깨지 못하게 한다.
    val texts: Map<LanguageCode, String> = texts.toMap()

    init {
        require(this.texts.keys == TARGET_LANGUAGES) {
            "9개 대상 언어 전수를 담아야 합니다: 기대=$TARGET_LANGUAGES, 실제=${this.texts.keys}"
        }
        require(this.texts.values.none { it.isBlank() }) { "번역 값은 blank 일 수 없습니다" }
    }

    fun byCode(): Map<String, String> = texts.mapKeys { (lang, _) -> lang.code }

    companion object {
        // KO 는 원문(source)이라 번역 대상이 아니다 — 헌법 V 사전 번역 정책의 9개 대상 언어.
        val TARGET_LANGUAGES: Set<LanguageCode> =
            LanguageCode.entries.filterNot { it == LanguageCode.KO }.toSet()
    }
}
