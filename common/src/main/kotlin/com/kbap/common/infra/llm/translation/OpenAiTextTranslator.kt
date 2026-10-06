package com.kbap.common.infra.llm.translation

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.metering.LlmCallCostIncurred
import com.kbap.common.infra.llm.model.LlmPricing
import com.kbap.common.port.llm.TextTranslator
import com.kbap.common.port.llm.TranslatedText
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

class OpenAiTextTranslator(
    private val chatModel: ChatModel,
    private val pricing: LlmPricing,
    private val configuredModelName: String,
    private val eventPublisher: ApplicationEventPublisher,
    private val newMarker: () -> String = { "LANG-" + UUID.randomUUID().toString().take(8) },
) : TextTranslator {
    override fun translate(text: String, target: LanguageCode): TranslatedText {
        val options = (chatModel.defaultOptions as? OpenAiChatOptions)?.mutate() ?: OpenAiChatOptions.builder()
        options.maxCompletionTokens(maxOutputTokens(text))
        val marker = newMarker()
        val response = chatModel.call(Prompt(listOf(SystemMessage(TranslationPrompt.system(target, marker)), UserMessage(text)), options.build()))
        val usage = response.metadata.usage
        val input = (usage.promptTokens ?: 0).toLong()
        val output = (usage.completionTokens ?: 0).toLong()
        val modelName = response.metadata.model?.takeIf { it.isNotBlank() } ?: configuredModelName
        log.info("번역 LLM 호출 — model={}, target={}, sourceChars={}, inputTokens={}, outputTokens={}", modelName, target.code, text.length, input, output)
        runCatching {
            eventPublisher.publishEvent(
                LlmCallCostIncurred(
                    modelName = modelName,
                    inputTokens = input,
                    outputTokens = output,
                    costUsd = BigDecimal.valueOf(pricing.costUsd(input, output)).setScale(6, RoundingMode.HALF_UP),
                    costKrw = BigDecimal.valueOf(pricing.costKrw(input, output)).setScale(2, RoundingMode.HALF_UP),
                ),
            )
        }.onFailure { log.warn("번역 LLM 비용 이벤트 발행 실패", it) }
        val finishReason = response.result?.metadata?.finishReason
        check(finishReason.equals(FINISHED, ignoreCase = true)) {
            "번역이 끝까지 생성됐다는 표식이 없다(finishReason=${finishReason?.lowercase()}) — 잘렸거나 확인할 수 없는 번역은 쓰지 않는다"
        }
        return split(response.result?.output?.text.orEmpty(), text, marker)
    }

    private fun split(output: String, source: String, marker: String): TranslatedText {
        val headerStart = output.indexOfFirst { !it.isWhitespace() }
        val headerEnd = if (headerStart < 0) -1 else output.indexOf('\n', headerStart)
        val firstLine = if (headerStart < 0) "" else if (headerEnd < 0) output.substring(headerStart) else output.substring(headerStart, headerEnd)
        val translated = if (marker !in firstLine) {
            TranslatedText(output, null)
        } else {
            val afterHeader = if (headerEnd < 0) "" else output.substring(headerEnd + 1)
            val translation = if (LEADING_BLANK_LINES.containsMatchIn(source)) afterHeader else afterHeader.replaceFirst(LEADING_BLANK_LINES, "")
            TranslatedText(translation, languageTagIn(firstLine.substringAfter(marker)))
        }
        check(marker !in translated.text) { "번역문에 머리줄 표식이 남아 있다 — 표식이 섞인 번역은 쓰지 않는다" }
        return translated
    }

    private fun languageTagIn(afterMarker: String): String {
        val words = afterMarker.trim(*HEADER_DECORATION).split(WHITESPACE).filter { it.isNotEmpty() }
        val sentenceLike = words.size > 1 && !words[1].startsWith('(')
        return if (sentenceLike) "" else words.firstOrNull().orEmpty().trim(*HEADER_DECORATION)
    }

    companion object {
        private val log = LoggerFactory.getLogger(OpenAiTextTranslator::class.java)

        private const val FINISHED = "stop"
        private val LEADING_BLANK_LINES = Regex("^(?:[ \\t]*\\r?\\n)+")
        private val HEADER_DECORATION = charArrayOf(' ', '\t', '\r', ':', '`', '*', '"', '\'', '<', '>', '.', ',')
        private val WHITESPACE = Regex("\\s+")

        const val OUTPUT_TOKEN_BASE = 2048
        const val OUTPUT_TOKENS_PER_SOURCE_CHAR = 6

        fun maxOutputTokens(text: String): Int = OUTPUT_TOKEN_BASE + text.length * OUTPUT_TOKENS_PER_SOURCE_CHAR
    }
}
