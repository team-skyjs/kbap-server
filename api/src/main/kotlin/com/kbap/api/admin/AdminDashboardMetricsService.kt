package com.kbap.api.admin

import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.metering.LlmCallCostJpaRepository
import com.kbap.common.domain.scan.ScanHistoryJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
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
    fun getMetrics(): AdminDashboardMetricsView {
        val today = LocalDate.now()
        val from = today.minusDays(6).atStartOfDay()
        return AdminDashboardMetricsView(
            totalActiveMembers = memberRepository.countByMemberStatus(MemberStatus.ACTIVE),
            weeklyScans = weeklyMetrics(today, scanHistoryRepository.countDailySince(from).associate { it.date to it.count }),
            weeklyNewFoods = weeklyMetrics(today, foodRepository.countDailyCreatedSince(from).associate { it.date to it.count }),
            weeklyLlmCostUsd = weeklyCosts(today, llmCallCostRepository.sumDailyCostUsdSince(from).associate { it.date to it.costUsd }),
        )
    }

    private fun weeklyMetrics(today: LocalDate, countsByDate: Map<LocalDate, Long>): List<DailyMetricView> {
        val counts = weekDates(today).map { it to (countsByDate[it] ?: 0L) }
        val max = counts.maxOf { it.second }
        return counts.map { (date, count) -> DailyMetricView(date, dayLabel(date), count, heightPct(count, max)) }
    }

    private fun weeklyCosts(today: LocalDate, costsByDate: Map<LocalDate, BigDecimal>): List<DailyCostView> {
        val costs = weekDates(today).map { it to (costsByDate[it] ?: BigDecimal.ZERO) }
        val max = costs.maxOf { it.second }
        return costs.map { (date, cost) -> DailyCostView(date, dayLabel(date), cost, heightPct(cost, max)) }
    }

    private fun heightPct(value: Long, max: Long): Int = if (max == 0L) 0 else (value * 100 / max).toInt()

    private fun heightPct(value: BigDecimal, max: BigDecimal): Int =
        if (max.signum() == 0) 0
        else value.multiply(BigDecimal(100)).divide(max, 0, RoundingMode.HALF_UP).toInt()

    private fun weekDates(today: LocalDate): List<LocalDate> = (6L downTo 0L).map(today::minusDays)

    private fun dayLabel(date: LocalDate): String =
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
}

data class AdminDashboardMetricsView(
    val totalActiveMembers: Long,
    val weeklyScans: List<DailyMetricView>,
    val weeklyNewFoods: List<DailyMetricView>,
    val weeklyLlmCostUsd: List<DailyCostView>,
)

data class DailyMetricView(
    val date: LocalDate,
    val dayLabel: String,
    val count: Long,
    val heightPct: Int,
)

data class DailyCostView(
    val date: LocalDate,
    val dayLabel: String,
    val costUsd: BigDecimal,
    val heightPct: Int,
)
