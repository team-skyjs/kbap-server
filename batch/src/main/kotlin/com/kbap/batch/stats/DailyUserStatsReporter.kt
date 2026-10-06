package com.kbap.batch.stats

import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.dto.NewMemberRow
import com.kbap.common.port.teamchannel.TeamChannelSender
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean

class DailyUserStatsReporter(
    private val memberRepository: MemberJpaRepository,
    private val sender: TeamChannelSender?,
    private val clock: Clock,
    private val excludedMemberIds: Set<Long>,
    private val enabled: Boolean = true,
) {
    fun report(): Outcome {
        if (!enabled) {
            logger.info("일일 유저 통계 — 이 환경에서는 꺼져 있어 건너뜁니다(kbap.batch.user-stats.enabled=false)")
            return Outcome.SKIPPED
        }
        if (sender == null) {
            if (skipReported.compareAndSet(false, true)) {
                logger.error("일일 유저 통계 — Slack 웹훅 URL 이 없어 발송하지 않습니다(SLACK_STATS_WEBHOOK_URL 미주입). 태스크 정의에 시크릿이 들어갔는지 확인하세요")
            } else {
                logger.warn("일일 유저 통계 — Slack 웹훅 URL 이 없어 발송을 건너뜁니다(SLACK_STATS_WEBHOOK_URL 미설정)")
            }
            return Outcome.SKIPPED
        }
        val text = render(collect())
        try {
            sender.send(text)
        } catch (first: RuntimeException) {
            logger.error("일일 유저 통계 발송 실패 — 1회 재시도합니다", first)
            sender.send(text)
        }
        return Outcome.SENT
    }

    fun collect(): DailyUserStats {
        val today = ZonedDateTime.now(clock.withZone(SEOUL)).toLocalDate()
        val yesterday = today.minusDays(1)
        val ids = excludedMemberIds.ifEmpty { NO_EXCLUSION }
        val rows = memberRepository.findRealMembersCreatedBetween(jvmStartOf(yesterday.minusDays(1)), jvmStartOf(today), ids)
        val yesterdayStart = jvmStartOf(yesterday)
        val (newMembers, previousMembers) = rows.partition { !it.createdAt.isBefore(yesterdayStart) }
        return DailyUserStats(
            date = yesterday,
            newMembers = newMembers.size,
            previousDayNewMembers = previousMembers.size,
            activeMembers = memberRepository.countActiveRealMembers(ids),
            byCountry = newMembers.groupingBy { it.countryCode ?: UNSET_COUNTRY }.eachCount(),
            byPlatform = newMembers.groupingBy(::platformLabel).eachCount(),
        )
    }

    private fun platformLabel(row: NewMemberRow): String = when (row.platform) {
        "IOS" -> "iOS"
        "ANDROID" -> "Android"
        else -> UNKNOWN_PLATFORM
    }

    private fun jvmStartOf(date: LocalDate): LocalDateTime =
        date.atStartOfDay(SEOUL).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()

    enum class Outcome { SENT, SKIPPED }

    companion object {
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        private val NO_EXCLUSION = setOf(-1L)
        const val UNSET_COUNTRY = "미설정"
        const val UNKNOWN_PLATFORM = "미상"
        private val logger = LoggerFactory.getLogger(DailyUserStatsReporter::class.java)
        private val skipReported = AtomicBoolean(false)

        fun render(stats: DailyUserStats): String {
            val delta = stats.newMembers - stats.previousDayNewMembers
            val signed = if (delta > 0) "+$delta" else "$delta"
            return listOf(
                "📊 kbap 일일 유저 통계 — ${stats.date} (KST)",
                "• 신규 가입: ${stats.newMembers}명 (전일 ${stats.previousDayNewMembers}명, $signed)",
                "• 활성 유저(현재): ${stats.activeMembers}명",
                "• 신규 국가별: ${breakdown(stats.byCountry)}",
                "• 신규 플랫폼별: ${breakdown(stats.byPlatform)}",
                "_로봇(Play 사전출시)·시드·리뷰 봇 계정 제외_",
            ).joinToString("\n")
        }

        private fun breakdown(counts: Map<String, Int>): String =
            if (counts.isEmpty()) "없음"
            else counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .joinToString(" · ") { "${it.key} ${it.value}" }
    }
}

data class DailyUserStats(
    val date: LocalDate,
    val newMembers: Int,
    val previousDayNewMembers: Int,
    val activeMembers: Long,
    val byCountry: Map<String, Int>,
    val byPlatform: Map<String, Int>,
)
