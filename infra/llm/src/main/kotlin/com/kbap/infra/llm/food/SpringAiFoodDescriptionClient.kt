package com.kbap.infra.llm.food

import com.kbap.core.food.FoodDescriptionClient
import com.kbap.core.food.FoodDescriptionContent
import com.kbap.core.food.TargetLanguageTexts
import com.kbap.core.lang.LanguageCode
import com.kbap.infra.llm.client.LlmModelCaller
import com.kbap.infra.llm.model.LlmChatRequest

class SpringAiFoodDescriptionClient(
    private val caller: LlmModelCaller,
    private val parser: FoodContentJsonParser = FoodContentJsonParser(),
) : FoodDescriptionClient {

    override fun call(koreanName: String): FoodDescriptionContent {
        val raw = caller.call(LlmChatRequest(prompt = promptOf(koreanName), system = SYSTEM_PROMPT))
        val response = parser.parse<DescriptionResponse>(raw)
        return FoodDescriptionContent(
            description = response.description,
            translations = TargetLanguageTexts(response.toTranslationsByLanguage()),
            spiciness = response.spiciness,
        )
    }

    private fun promptOf(koreanName: String): String =
        """
        아래 한국어 음식에 대한 소개 콘텐츠를 생성하라.
        음식명: "$koreanName"

        규칙:
        - description: 한국어 음식 설명. 반드시 255자 이하. 빈 값·"설명 준비 중" 금지.
        - spiciness: 맵기 정도를 0(안 매움)~10(매우 매움) 정수로.
        - translations: description 을 9개 대상 언어로 번역. 코드 = ${TARGET_CODES.joinToString(", ")}. 9개 전수·빈 값 금지.
        - 아래 JSON 형식으로만 답하라(설명·코드펜스 없이):
        {"description": "...", "spiciness": 3, "translations": {"en": "...", "ja": "..."}}
        """.trimIndent()

    data class DescriptionResponse(
        val description: String = "",
        val spiciness: Int = -1,
        val translations: Map<String, String> = emptyMap(),
    ) {
        fun toTranslationsByLanguage(): Map<LanguageCode, String> =
            translations.mapNotNull { (code, text) ->
                LanguageCode.entries.firstOrNull { it.code == code }?.let { it to text }
            }.toMap()
    }

    companion object {
        private const val SYSTEM_PROMPT = "너는 한국 음식을 소개하는 작가다. 반드시 지정된 JSON 형식으로만 답한다."
        private val TARGET_CODES = TargetLanguageTexts.TARGET_LANGUAGES.map { it.code }
    }
}
