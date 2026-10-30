package com.kbap.api.processedfood

import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Date
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ln

@Component
class ProcessedFoodPopularity(
    private val jdbc: JdbcTemplate,
    private val products: ProcessedFoodProductJpaRepository,
    private val cvsCatalog: ProcessedFoodCvsCatalog,
    private val candidateIndex: ProcessedFoodCandidateIndex,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var appScores: Map<String, AppScore>? = null

    data class Entry(val id: Long, val groupKey: String, val seedPercentile: Double)

    data class AppScore(val percentile: Double, val alpha: Double)

    fun order(entries: List<Entry>): List<Long> {
        val scores = appScores ?: return entries.map { it.id }
        return entries.sortedByDescending { entry ->
            val app = scores[entry.groupKey] ?: return@sortedByDescending entry.seedPercentile
            (1 - app.alpha) * entry.seedPercentile + app.alpha * app.percentile
        }.map { it.id }
    }

    fun seedPercentileOf(rank: Int): Double = SEED_FLOOR + SEED_FLOOR * (1 - (rank - 1).toDouble() / cvsCatalog.seedLines.size.coerceAtLeast(1))

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() = refresh()

    @Scheduled(cron = "\${kbap.processed-food.popularity.cron:0 30 4 * * *}", zone = "Asia/Seoul")
    fun refresh() {
        try {
            appScores = compute(LocalDate.now(SEOUL))
        } catch (e: RuntimeException) {
            log.warn("가공식품 앱 인기 점수 계산 실패 — 이전 점수(없으면 시드 순서)를 유지한다", e)
        }
    }

    internal fun compute(today: LocalDate): Map<String, AppScore> {
        val catalogGroupOf = products.findAllByReportNoIn(cvsCatalog.reportNos)
            .associate { it.id to ProcessedFoodGrouping.keyOf(it.normalizedName, it.displayMaker()) }
        if (catalogGroupOf.isEmpty()) return emptyMap()
        val groupOf = candidateIndex.groupMemberIds(catalogGroupOf.keys)
            .flatMap { (catalogId, members) -> members.map { it to catalogGroupOf.getValue(catalogId) } }
            .toMap() + catalogGroupOf
        val since = Date.valueOf(today.minusDays(WINDOW_DAYS))
        val excluded = EXCLUDED_MEMBER_IDS + jdbc.queryForList("SELECT id FROM member WHERE is_bot = 1", Long::class.java)
        val ids = groupOf.keys.joinToString(",")
        val reviews = jdbc.query(
            "SELECT processed_food_id, member_id, DATE(created_at) FROM food_review " +
                "WHERE status = 'ACTIVE' AND processed_food_id IN ($ids) AND created_at >= ?",
            { rs, _ -> Signal(rs.getLong(1), rs.getLong(2), "REVIEW", rs.getDate(3).toLocalDate()) },
            since,
        )
        val logged = jdbc.query(
            "SELECT processed_food_id, member_id, signal_type, signal_date FROM processed_food_signal " +
                "WHERE signal_date >= ? AND processed_food_id IN ($ids)",
            { rs, _ -> Signal(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getDate(4).toLocalDate()) },
            since,
        )
        val signals = (reviews + logged).filter { it.memberId !in excluded && it.processedFoodId in groupOf }.distinct()
        val raw = signals.groupBy { groupOf.getValue(it.processedFoodId) }.mapValues { (_, group) ->
            val reviewCount = group.count { it.type == "REVIEW" }
            val evidence = 3.0 * reviewCount + group.filter { it.type == "SCAN_MATCH" }.map { it.memberId }.distinct().size
            Raw(ln(1 + group.sumOf { WEIGHTS.getValue(it.type) }), evidence)
        }
        val groups = catalogGroupOf.values.toSet()
        val values = groups.map { raw[it]?.score ?: 0.0 }
        return groups.associateWith { group ->
            val score = raw[group]?.score ?: 0.0
            val percentile = if (score <= 0.0) 0.0 else values.count { it < score }.toDouble() / values.size
            val evidence = raw[group]?.evidence ?: 0.0
            AppScore(percentile, evidence / (evidence + ALPHA_HALF))
        }
    }

    private data class Signal(val processedFoodId: Long, val memberId: Long, val type: String, val day: LocalDate)

    private data class Raw(val score: Double, val evidence: Double)

    companion object {
        const val NON_SEED_PERCENTILE = 0.2
        private const val SEED_FLOOR = 0.5
        private const val WINDOW_DAYS = 90L
        private const val ALPHA_HALF = 30.0
        private val EXCLUDED_MEMBER_IDS = setOf(10L, 12L, 18L)
        private val WEIGHTS = mapOf("REVIEW" to 5.0, "SCAN_MATCH" to 3.0, "VIEW" to 1.0)
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
