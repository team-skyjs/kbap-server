package com.kbap.common.infra.llm.translation

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.metering.LlmCallCostIncurred
import com.kbap.common.infra.llm.model.LlmPricing
import com.kbap.common.port.llm.TextTranslator
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.math.RoundingMode

class OpenAiTextTranslator(
    private val chatModel: ChatModel,
    private val pricing: LlmPricing,
    private val configuredModelName: String,
    private val eventPublisher: ApplicationEventPublisher,
) : TextTranslator {
    override fun translate(text: String, target: LanguageCode): String = ""

    companion object {
        const val OUTPUT_TOKEN_BASE = 2048
        const val OUTPUT_TOKENS_PER_SOURCE_CHAR = 6

        fun maxOutputTokens(text: String): Int = OUTPUT_TOKEN_BASE + text.length * OUTPUT_TOKENS_PER_SOURCE_CHAR

        fun languageNameOf(target: LanguageCode): String = when (target) {
            LanguageCode.KO -> "Korean"
            LanguageCode.ZH_HANS -> "Simplified Chinese"
            LanguageCode.EN -> "English"
            LanguageCode.JA -> "Japanese"
            LanguageCode.ZH_HANT -> "Traditional Chinese"
            LanguageCode.VI -> "Vietnamese"
            LanguageCode.ID -> "Indonesian"
            LanguageCode.TH -> "Thai"
            LanguageCode.RU -> "Russian"
            LanguageCode.ES -> "Spanish"
        }
    }
}
