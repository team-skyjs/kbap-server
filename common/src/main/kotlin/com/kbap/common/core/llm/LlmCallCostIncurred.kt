package com.kbap.common.core.llm

import java.math.BigDecimal

data class LlmCallCostIncurred(
    val modelName: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: BigDecimal,
    val costKrw: BigDecimal,
)
