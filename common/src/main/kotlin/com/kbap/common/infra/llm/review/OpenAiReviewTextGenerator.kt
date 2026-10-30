package com.kbap.common.infra.llm.review

import com.kbap.common.domain.metering.LlmCallCostIncurred
import com.kbap.common.domain.review.model.ReviewBotForbiddenCategory
import com.kbap.common.infra.llm.model.LlmPricing
import com.kbap.common.port.llm.ReviewDraftRequest
import com.kbap.common.port.llm.ReviewTextGenerator
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.math.RoundingMode

class OpenAiReviewTextGenerator(
    private val chatModel: ChatModel,
    private val pricing: LlmPricing,
    private val configuredModelName: String,
    private val eventPublisher: ApplicationEventPublisher,
) : ReviewTextGenerator {
    override fun generate(request: ReviewDraftRequest): String {
        val response = chatModel.call(Prompt(listOf(SystemMessage(SYSTEM_PROMPT), UserMessage(userPrompt(request)))))
        val usage = response.metadata.usage
        val input = (usage.promptTokens ?: 0).toLong()
        val output = (usage.completionTokens ?: 0).toLong()
        runCatching {
            eventPublisher.publishEvent(
                LlmCallCostIncurred(
                    modelName = response.metadata.model?.takeIf { it.isNotBlank() } ?: configuredModelName,
                    inputTokens = input,
                    outputTokens = output,
                    costUsd = BigDecimal.valueOf(pricing.costUsd(input, output)).setScale(6, RoundingMode.HALF_UP),
                    costKrw = BigDecimal.valueOf(pricing.costKrw(input, output)).setScale(2, RoundingMode.HALF_UP),
                ),
            )
        }.onFailure { log.warn("리뷰 봇 LLM 비용 이벤트 발행 실패", it) }
        return response.result?.output?.text?.trim().orEmpty()
    }

    private fun userPrompt(request: ReviewDraftRequest): String =
        """
        Write a very short customer review of this Korean dish.
        Dish: ${request.foodName}
        About the dish: ${request.description}
        Main ingredients: ${request.ingredients.joinToString(", ").ifBlank { "unknown" }}
        Star rating the reviewer gave: ${request.rating} out of 5 (match the tone to it)
        Language: write the whole review in ${request.language} only.
        Length: at most ${request.maxCharacters} characters in total (it must fit in two lines of an app card).
        """.trimIndent()

    companion object {
        val log = LoggerFactory.getLogger(OpenAiReviewTextGenerator::class.java)

        val SYSTEM_PROMPT: String =
            listOf(
                "You write casual food reviews as a traveler who just ate the dish in Korea, like a quick message to a friend during the trip.",
                "Rules:",
                "- 1 or 2 short sentences in light, friendly internet-casual tone that sounds natural in the target language " +
                    "using only casual or internet expressions a native speaker of that language would use without mixing in expressions from other languages. " +
                    "Keep within the character limit given in the request.",
                "- Plain text only, no title, no quotes, no emoji, no hashtags, no lists.",
                "- Talk only about taste, texture, spiciness, portion feel, and how it pairs with rice or drinks.",
            ).plus(ReviewBotForbiddenCategory.entries.map { "- ${it.promptRule}" }).joinToString("\n")
    }
}
