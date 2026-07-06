package com.meogo.infra.llm.model

data class LlmModelFailure(
    val modelId: LlmModelId,
    val message: String,
)
