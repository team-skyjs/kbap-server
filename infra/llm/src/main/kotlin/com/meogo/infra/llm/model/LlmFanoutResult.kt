package com.meogo.infra.llm.model

data class LlmFanoutResult(
    val successes: List<LlmChatResult>,
    val failures: List<LlmModelFailure>,
) {
    fun isAllFailed(): Boolean = successes.isEmpty()

    fun attemptedCount(): Int = successes.size + failures.size
}
