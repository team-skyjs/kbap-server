package com.kbap.infra.llm.food

import com.kbap.common.core.food.FoodNameTranslationClient
import com.kbap.common.core.food.TargetLanguageTexts
import com.kbap.common.core.lang.LanguageCode
import com.kbap.infra.llm.client.LlmModelCaller
import com.kbap.infra.llm.model.LlmChatRequest

class SpringAiFoodNameTranslationClient(
    private val caller: LlmModelCaller,
) : FoodNameTranslationClient {

    override fun call(koreanName: String): TargetLanguageTexts {
        val raw = caller.call(LlmChatRequest(prompt = promptOf(koreanName), system = SYSTEM_PROMPT))
        val response = FoodContentJsonParser.parse<TranslationResponse>(raw)
        return TargetLanguageTexts(response.toTextsByLanguage())
    }

    private fun promptOf(koreanName: String): String =
        """
        당신은 한식 메뉴 데이터베이스 담당자입니다. 아래 음식명을 외국인 손님이 메뉴판에서 읽을
        번역명으로 만드세요.
        음식명: "$koreanName"

        ## 생성 항목

        translations: 음식명을 9개 언어로 번역한 값
        - 언어 키(9개, 순서 고정): $KEY_ORDER
        - 9개 전수 채우고 빈 값 금지.

        ## 번역 규칙

        - 널리 알려진 한식은 그 언어권에서 통용되는 표기를 쓴다 (Kimchi, Bibimbap, キンパ, 泡菜).
        - 통용 표기가 없으면 음식을 알아볼 수 있게 짧게 의역한다 (된장술밥 → Soybean Paste Rice).
        - 메뉴판에 올릴 이름이므로 간결하게. 문장·설명·괄호 병기 금지.
        - 각 언어의 문자 체계를 따른다 (ja 는 일본어 표기, ru 는 키릴 문자, th 는 태국 문자).

        ## 출력 형식

        아래 JSON 한 줄로만 답하세요(코드펜스·다른 설명 없이). 예시(치즈볼):
        {"translations": {"en": "Cheese Balls", "es": "Bolas de queso", "id": "Bola keju", "ja": "チーズボール", "ru": "Сырные шарики", "th": "ชีสบอล", "vi": "Viên phô mai chiên", "zh-Hans": "芝士球", "zh-Hant": "起司球"}}

        ## 입력이 불완전할 때

        - 오탈자·띄어쓰기 오류로 보이면 가장 유사한 실제 한식 메뉴로 추론해 고친 이름 기준으로
          번역한다 (김치찌게 → 김치찌개, 짜장면/자장면 동일 취급).
        - 상호·수식어가 붙어 있으면 음식 본체 기준으로 번역한다 (원조할매국밥 → 국밥,
          왕돈까스(대) → 왕돈까스).
        - 로제떡볶이·마라탕면처럼 합성·변형 메뉴는 구성 요소를 조합해 번역한다.
        - 파스타·피자 같은 외래 음식은 각 언어의 통용 표기를 그대로 쓴다.
        - 그래도 모르는 음식이면 건너뛰지 말고 음식명의 구성 요소를 기준으로 번역한다.
        """.trimIndent()

    data class TranslationResponse(val translations: Map<String, String> = emptyMap()) {
        fun toTextsByLanguage(): Map<LanguageCode, String> =
            translations.mapNotNull { (code, text) ->
                LanguageCode.entries.firstOrNull { it.code == code }?.let { it to text }
            }.toMap()
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "당신은 한식 메뉴 데이터베이스 담당자입니다. 반드시 지정된 JSON 형식 한 줄로만 답합니다."
        private const val KEY_ORDER = "en, es, id, ja, ru, th, vi, zh-Hans, zh-Hant"
    }
}
