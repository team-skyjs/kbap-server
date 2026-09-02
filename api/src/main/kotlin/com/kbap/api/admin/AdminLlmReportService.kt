package com.kbap.api.admin

import com.kbap.common.domain.metering.LlmCallCostJpaRepository
import com.kbap.common.domain.metering.dto.DailyModelCostSum
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

@Service
class AdminLlmReportService(
    private val llmCallCostRepository: LlmCallCostJpaRepository,
) {
    @Transactional(readOnly = true)
    fun getLlmCostReport(days: Int): AdminLlmCostReportResponse {
        require(days in 1..MAX_REPORT_DAYS) { "days 는 1..$MAX_REPORT_DAYS 여야 합니다: $days" }
        val today = LocalDate.now()
        val sumsByDate = llmCallCostRepository
            .sumDailyByModelSince(today.minusDays(days - 1L).atStartOfDay())
            .groupBy(DailyModelCostSum::date)
        val dailyCosts = (days - 1L downTo 0L).map(today::minusDays).map { date ->
            val models = sumsByDate[date].orEmpty()
                .sortedByDescending { it.costUsd }
                .map(AdminLlmModelCostResponse::from)
            AdminLlmDailyCostResponse(
                date = date,
                callCount = models.sumOf { it.callCount },
                costUsd = models.fold(BigDecimal.ZERO) { acc, model -> acc + model.costUsd },
                models = models,
            )
        }
        return AdminLlmCostReportResponse(days = dailyCosts)
    }

    companion object {
        const val MAX_REPORT_DAYS = 30
    }
}

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
