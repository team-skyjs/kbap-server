package com.kbap.infra.llm.client

import com.kbap.infra.llm.model.LlmChatRequest
import com.kbap.infra.llm.model.LlmModelId

interface LlmModelCaller {
    val modelId: LlmModelId

    fun call(request: LlmChatRequest): String
}
