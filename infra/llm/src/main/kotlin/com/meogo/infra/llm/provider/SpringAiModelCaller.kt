package com.meogo.infra.llm.provider

import com.meogo.infra.llm.client.LlmModelCaller
import com.meogo.infra.llm.model.LlmChatRequest
import com.meogo.infra.llm.model.LlmModelId
import com.meogo.infra.llm.model.LlmPricing
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt

class SpringAiModelCaller(
    override val modelId: LlmModelId,
    private val chatModel: ChatModel,
    private val pricing: LlmPricing = LlmPricing.UNPRICED,
) : LlmModelCaller {

    private val logger = LoggerFactory.getLogger(SpringAiModelCaller::class.java)

    override fun call(request: LlmChatRequest): String {
        val response = chatModel.call(promptOf(request))
        logTokenUsage(response)
        return response.results.firstOrNull()?.output?.text.orEmpty()
    }

    private fun logTokenUsage(response: ChatResponse) {
        val usage = response.metadata.usage
        val promptTokens = (usage.promptTokens ?: 0).toLong()
        val completionTokens = (usage.completionTokens ?: 0).toLong()
        logger.info(
            "LLM 토큰 사용량 model={} promptTokens={} completionTokens={} totalTokens={} costUsd={} costKrw={}",
            modelId,
            promptTokens,
            completionTokens,
            usage.totalTokens,
            "%.6f".format(pricing.costUsd(promptTokens, completionTokens)),
            "%.2f".format(pricing.costKrw(promptTokens, completionTokens)),
        )
    }

    private fun promptOf(request: LlmChatRequest): Prompt {
        val system = request.system
        return if (system.isNullOrBlank()) {
            Prompt(UserMessage(request.prompt))
        } else {
            Prompt(listOf(SystemMessage(system), UserMessage(request.prompt)))
        }
    }
}
