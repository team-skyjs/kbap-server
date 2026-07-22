package com.kbap.infra.llm.food

import com.kbap.core.food.FoodNameTranslationClient
import com.kbap.core.food.TargetLanguageTexts
import com.kbap.core.lang.LanguageCode
import com.kbap.infra.llm.client.LlmModelCaller
import com.kbap.infra.llm.model.LlmChatRequest

class SpringAiFoodNameTranslationClient(
    private val caller: LlmModelCaller,
    private val parser: FoodContentJsonParser = FoodContentJsonParser(),
) : FoodNameTranslationClient {

    override fun call(koreanName: String): TargetLanguageTexts {
        val raw = caller.call(LlmChatRequest(prompt = promptOf(koreanName), system = SYSTEM_PROMPT))
        val response = parser.parse<TranslationResponse>(raw)
        return TargetLanguageTexts(response.toTextsByLanguage())
    }

    private fun promptOf(koreanName: String): String =
        """
        아래 한국어 음식명을 9개 대상 언어로 번역하라.
        음식명: "$koreanName"

        대상 언어 코드: ${TARGET_CODES.joinToString(", ")}
        - 각 언어 코드를 key 로, 번역된 음식명을 value 로 담는다.
        - 9개 언어를 하나도 빠뜨리지 말고 모두 채운다. 빈 값 금지.
        - 아래 JSON 형식으로만 답하라(설명·코드펜스 없이):
        {"translations": {"en": "...", "ja": "...", "zh-Hans": "..."}}
        """.trimIndent()

    data class TranslationResponse(val translations: Map<String, String> = emptyMap()) {
        fun toTextsByLanguage(): Map<LanguageCode, String> =
            translations.mapNotNull { (code, text) ->
                LanguageCode.entries.firstOrNull { it.code == code }?.let { it to text }
            }.toMap()
    }

    companion object {
        private const val SYSTEM_PROMPT = "너는 한국 음식명을 여러 언어로 번역하는 번역가다. 반드시 지정된 JSON 형식으로만 답한다."
        private val TARGET_CODES = TargetLanguageTexts.TARGET_LANGUAGES.map { it.code }
    }
}
