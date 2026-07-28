package com.kbap.infra.llm.food

import com.kbap.common.core.food.FoodDescriptionClient
import com.kbap.common.core.food.FoodDescriptionContent
import com.kbap.common.core.food.TargetLanguageTexts
import com.kbap.common.core.lang.LanguageCode
import com.kbap.infra.llm.client.LlmModelCaller
import com.kbap.infra.llm.model.LlmChatRequest

class SpringAiFoodDescriptionClient(
    private val caller: LlmModelCaller,
) : FoodDescriptionClient {

    override fun call(koreanName: String): FoodDescriptionContent {
        val raw = caller.call(LlmChatRequest(prompt = promptOf(koreanName), system = SYSTEM_PROMPT))
        val response = FoodContentJsonParser.parse<DescriptionResponse>(raw)
        return FoodDescriptionContent(
            description = response.description,
            translations = TargetLanguageTexts(response.toTranslationsByLanguage()),
        )
    }

    private fun promptOf(koreanName: String): String =
        """
        당신은 한식 메뉴 데이터베이스 담당자입니다. 아래 음식의 소개 데이터를 생성하세요.
        음식명: "$koreanName"

        ## 생성 항목

        1. description: 한국어 한 줄 설명
           - 요리법·주재료가 드러나는 한 문장. 과장 없이 사실적으로.
           - 반드시 255자 이하. 빈 값·"설명 준비 중" 같은 템플릿 문구 금지.
        2. translations: description 을 9개 언어로 실제 번역한 값 (템플릿 문구·원문 복사 금지)
           - 언어 키(9개, 순서 고정): $KEY_ORDER
           - 9개 전수 채우고 빈 값 금지.

        ## 출력 형식

        아래 JSON 한 줄로만 답하세요(코드펜스·다른 설명 없이). 예시(치즈볼):
        {"description": "치즈를 넣은 반죽을 둥글게 튀긴 사이드 메뉴", "translations": {"en": "Round fried dough balls filled with cheese.", "es": "Bolitas de masa fritas rellenas de queso.", "id": "Bola adonan goreng berisi keju.", "ja": "チーズを入れた生地を丸く揚げたサイドメニュー。", "ru": "Обжаренные шарики из теста с сырной начинкой.", "th": "แป้งทอดกลมสอดไส้ชีส", "vi": "Bột chiên tròn nhân phô mai.", "zh-Hans": "面团包入芝士后炸成圆球的小吃。", "zh-Hant": "麵團包入起司後炸成圓球的小吃。"}}

        ## 규칙

        - 음식명에 오탈자·띄어쓰기 오류가 보이면 가장 유사한 실제 한식 메뉴로 추론해 그 음식
          기준으로 작성하세요 (김치찌게 → 김치찌개). 상호·수식어가 붙어 있으면 음식 본체 기준.
        - 모르는 음식이어도 건너뛰지 말고 일반적인 한식 지식 기준으로 작성하세요.
        - description 은 한국어 원문이고, translations 는 그 설명을 각 언어로 번역한 것입니다.
        """.trimIndent()

    data class DescriptionResponse(
        val description: String = "",
        val translations: Map<String, String> = emptyMap(),
    ) {
        fun toTranslationsByLanguage(): Map<LanguageCode, String> =
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
