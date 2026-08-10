package com.kbap.infra.llm.client

import com.kbap.infra.llm.model.LlmChatRequest

interface LlmModelCaller {
    fun call(request: LlmChatRequest): String
}
