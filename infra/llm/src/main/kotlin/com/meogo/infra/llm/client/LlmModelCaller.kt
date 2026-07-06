package com.meogo.infra.llm.client

import com.meogo.infra.llm.model.LlmChatRequest
import com.meogo.infra.llm.model.LlmModelId

interface LlmModelCaller {
    val modelId: LlmModelId

    fun call(request: LlmChatRequest): String
}
