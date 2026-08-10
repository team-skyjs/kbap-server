package com.kbap.infra.llm.provider

import com.kbap.infra.llm.client.LlmModelCaller
import com.kbap.infra.llm.model.LlmChatRequest
import com.kbap.infra.llm.model.LlmPricing
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt

class SpringAiModelCaller(
    private val modelName: String,
    private val chatModel: ChatModel,
    private val pricing: LlmPricing = LlmPricing.UNPRICED,
) : LlmModelCaller {

    private val logger = LoggerFactory.getLogger(SpringAiModelCaller::class.java)

    override fun call(request: LlmChatRequest): String {
        val response = chatModel.call(promptOf(request))
        logTokenUsage(response)
        val content = response.results.firstOrNull()?.output?.text.orEmpty()
        logger.debug("LLM 응답 본문 model={} content={}", modelName, content)
        return content
    }

    private fun logTokenUsage(response: ChatResponse) {
        val usage = response.metadata.usage
        val promptTokens = (usage.promptTokens ?: 0).toLong()
        val completionTokens = (usage.completionTokens ?: 0).toLong()
        logger.info(
            "LLM 토큰 사용량 model={} promptTokens={} completionTokens={} totalTokens={} costUsd={} costKrw={}",
            modelName,
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
