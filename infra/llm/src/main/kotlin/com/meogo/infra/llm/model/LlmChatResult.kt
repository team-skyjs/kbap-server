package com.meogo.infra.llm.model

data class LlmChatResult(
    val modelId: LlmModelId,
    val content: String,
)
