package com.kbap.api.admin

import com.kbap.common.domain.metering.dto.DailyModelCostSum
import java.math.BigDecimal
import java.time.LocalDate

data class AdminLlmCostReportResponse(
    val days: List<AdminLlmDailyCostResponse>,
)

data class AdminLlmDailyCostResponse(
    val date: LocalDate,
    val callCount: Long,
    val costUsd: BigDecimal,
    val models: List<AdminLlmModelCostResponse>,
)

data class AdminLlmModelCostResponse(
    val modelName: String,
    val callCount: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: BigDecimal,
) {
    companion object {
        fun from(sum: DailyModelCostSum): AdminLlmModelCostResponse =
            AdminLlmModelCostResponse(
                modelName = sum.modelName,
                callCount = sum.callCount,
                inputTokens = sum.inputTokens,
                outputTokens = sum.outputTokens,
                costUsd = sum.costUsd,
            )
    }
}
