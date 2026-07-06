package com.meogo.infra.llm.model

data class LlmChatRequest(
    val prompt: String,
    val system: String? = null,
) {
    init {
        require(prompt.isNotBlank()) { "prompt must not be blank" }
    }
}
