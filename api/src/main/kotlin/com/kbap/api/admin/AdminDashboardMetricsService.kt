package com.kbap.api.admin

import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.scan.ScanHistoryJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Service
class AdminDashboardMetricsService(
    private val memberRepository: MemberJpaRepository,
    private val scanHistoryRepository: ScanHistoryJpaRepository,
    private val foodRepository: FoodJpaRepository,
) {
    @Transactional(readOnly = true)
    fun getMetrics(): AdminDashboardMetricsView {
        val today = LocalDate.now()
        val from = today.minusDays(6).atStartOfDay()
        return AdminDashboardMetricsView(
            totalActiveMembers = memberRepository.countByMemberStatus(MemberStatus.ACTIVE),
            weeklyScans = weeklyMetrics(today, scanHistoryRepository.countDailySince(from).associate { it.date to it.count }),
            weeklyNewFoods = weeklyMetrics(today, foodRepository.countDailyCreatedSince(from).associate { it.date to it.count }),
        )
    }

    private fun weeklyMetrics(today: LocalDate, countsByDate: Map<LocalDate, Long>): List<DailyMetricView> {
        val counts = weekDates(today).map { it to (countsByDate[it] ?: 0L) }
        val max = counts.maxOf { it.second }
        return counts.map { (date, count) -> DailyMetricView(date, dayLabel(date), count, heightPct(count, max)) }
    }

    private fun heightPct(value: Long, max: Long): Int = if (max == 0L) 0 else (value * 100 / max).toInt()

    private fun weekDates(today: LocalDate): List<LocalDate> = (6L downTo 0L).map(today::minusDays)

    private fun dayLabel(date: LocalDate): String =
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
}

data class AdminDashboardMetricsView(
    val totalActiveMembers: Long,
    val weeklyScans: List<DailyMetricView>,
    val weeklyNewFoods: List<DailyMetricView>,
)

data class DailyMetricView(
    val date: LocalDate,
    val dayLabel: String,
    val count: Long,
    val heightPct: Int,
)
