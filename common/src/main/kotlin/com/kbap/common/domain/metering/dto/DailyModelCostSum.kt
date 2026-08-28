package com.kbap.common.domain.metering.dto

import java.math.BigDecimal
import java.time.LocalDate

data class DailyModelCostSum(
    val date: LocalDate,
    val modelName: String,
    val callCount: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: BigDecimal,
    val costKrw: BigDecimal,
)
