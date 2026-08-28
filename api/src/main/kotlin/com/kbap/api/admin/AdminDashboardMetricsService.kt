package com.kbap.api.admin

import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.metering.LlmCallCostJpaRepository
import com.kbap.common.domain.metering.dto.DailyModelCostSum
import com.kbap.common.domain.scan.ScanHistoryJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Service
class AdminDashboardMetricsService(
    private val memberRepository: MemberJpaRepository,
    private val scanHistoryRepository: ScanHistoryJpaRepository,
    private val foodRepository: FoodJpaRepository,
    private val llmCallCostRepository: LlmCallCostJpaRepository,
) {
    @Transactional(readOnly = true)
    fun getMetrics(days: Int = DEFAULT_DAYS): AdminDashboardMetricsView {
        require(days in DAYS_RANGE) { "days 는 ${DAYS_RANGE.first}..${DAYS_RANGE.last} 이어야 합니다: $days" }
        val today = LocalDate.now()
        val from = today.minusDays(days - 1L).atStartOfDay()
        return AdminDashboardMetricsView(
            days = days,
            totalActiveMembers = memberRepository.countByMemberStatus(MemberStatus.ACTIVE),
            weeklyScans = dailyMetrics(today, days, scanHistoryRepository.countDailySince(from).associate { it.date to it.count }),
            weeklyNewFoods = dailyMetrics(today, days, foodRepository.countDailyCreatedSince(from).associate { it.date to it.count }),
            llmCostDaily = llmCostDaily(today, days, llmCallCostRepository.sumDailyByModelSince(from)),
        )
    }

    private fun dailyMetrics(today: LocalDate, days: Int, countsByDate: Map<LocalDate, Long>): List<DailyMetricView> {
        val counts = dates(today, days).map { it to (countsByDate[it] ?: 0L) }
        val max = counts.maxOf { it.second }
        return counts.map { (date, count) -> DailyMetricView(date, dayLabel(date), count, heightPct(count, max)) }
    }

    private fun llmCostDaily(today: LocalDate, days: Int, sums: List<DailyModelCostSum>): List<LlmCostDailyView> {
        val byDate = sums.groupBy { it.date }
        return dates(today, days).reversed().map { date ->
            val models = byDate[date].orEmpty()
                .sortedByDescending { it.costUsd }
                .map { LlmCostModelView(it.modelName, it.callCount, it.inputTokens, it.outputTokens, it.costUsd, it.costKrw) }
            LlmCostDailyView(
                date = date,
                dayLabel = dayLabel(date),
                callCount = models.sumOf { it.callCount },
                costUsd = models.fold(BigDecimal.ZERO) { acc, m -> acc + m.costUsd },
                costKrw = models.fold(BigDecimal.ZERO) { acc, m -> acc + m.costKrw },
                models = models,
            )
        }
    }

    private fun heightPct(value: Long, max: Long): Int = if (max == 0L) 0 else (value * 100 / max).toInt()

    private fun dates(today: LocalDate, days: Int): List<LocalDate> = ((days - 1L) downTo 0L).map(today::minusDays)

    private fun dayLabel(date: LocalDate): String =
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)

    companion object {
        const val DEFAULT_DAYS = 7
        val DAYS_RANGE = 1..90
        const val LLM_COST_SCOPE_NOTE = "스캔 비전 + 이미지 생성 성공분만 집계(임베딩·실패분 제외)"
    }
}

data class AdminDashboardMetricsView(
    val days: Int,
    val totalActiveMembers: Long,
    val weeklyScans: List<DailyMetricView>,
    val weeklyNewFoods: List<DailyMetricView>,
    val llmCostDaily: List<LlmCostDailyView>,
)

data class DailyMetricView(
    val date: LocalDate,
    val dayLabel: String,
    val count: Long,
    val heightPct: Int,
)

data class LlmCostDailyView(
    val date: LocalDate,
    val dayLabel: String,
    val callCount: Long,
    val costUsd: BigDecimal,
    val costKrw: BigDecimal,
    val models: List<LlmCostModelView>,
)

data class LlmCostModelView(
    val modelName: String,
    val callCount: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: BigDecimal,
    val costKrw: BigDecimal,
)
