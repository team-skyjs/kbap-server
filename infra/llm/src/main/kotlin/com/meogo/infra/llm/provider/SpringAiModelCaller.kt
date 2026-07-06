package com.meogo.infra.llm.provider

import com.meogo.infra.llm.client.LlmModelCaller
import com.meogo.infra.llm.model.LlmChatRequest
import com.meogo.infra.llm.model.LlmModelId
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt

class SpringAiModelCaller(
    override val modelId: LlmModelId,
    private val chatModel: ChatModel,
) : LlmModelCaller {
    override fun call(request: LlmChatRequest): String {
        val response = chatModel.call(promptOf(request))
        return response.results.firstOrNull()?.output?.text.orEmpty()
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
