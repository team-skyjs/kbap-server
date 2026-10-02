package com.kbap.api.review

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class ReviewLanguageBackfill(
    private val jdbcTemplate: JdbcTemplate,
    private val detector: ReviewLanguageDetector,
    @Value("\${kbap.review.language-backfill.enabled:true}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady(): Thread? {
        if (!enabled) return null
        return Thread.ofPlatform().daemon().name("review-language-backfill").start {
            try {
                val result = backfill()
                log.info(
                    "리뷰 본문 언어 채우기 — 살펴본 리뷰 {}건, 채운 리뷰 {}건, 판별하지 못해 비워 둔 리뷰 {}건",
                    result.examined, result.filled, result.leftUnknown,
                )
            } catch (e: RuntimeException) {
                log.warn("리뷰 본문 언어 채우기 실패 — 다음 기동 때 다시 시도한다", e)
            }
        }
    }

    fun backfill(): ReviewLanguageBackfillResult {
        var lastId = 0L
        var examined = 0
        var filled = 0
        while (examined < MAX_ROWS_PER_RUN) {
            val page = fillPageAfter(lastId)
            if (page.examined == 0) break
            lastId = page.lastId
            examined += page.examined
            filled += page.filled
        }
        return ReviewLanguageBackfillResult(examined, filled)
    }

    private fun fillPageAfter(lastId: Long): FilledPage {
        val rows = jdbcTemplate.query(
            "SELECT id, content, version FROM food_review " +
                "WHERE id > ? AND language IS NULL AND content IS NOT NULL AND status = 'ACTIVE' ORDER BY id LIMIT ?",
            { rs, _ -> LegacyReview(rs.getLong("id"), rs.getString("content"), rs.getLong("version")) },
            lastId, PAGE_SIZE,
        )
        val filled = rows.sumOf { review ->
            detector.detect(review.content)?.let { language ->
                jdbcTemplate.update(
                    "UPDATE food_review SET language = ?, updated_at = updated_at WHERE id = ? AND language IS NULL AND version = ?",
                    language.code, review.id, review.version,
                )
            } ?: 0
        }
        return FilledPage(lastId = rows.lastOrNull()?.id ?: lastId, examined = rows.size, filled = filled)
    }

    private data class LegacyReview(val id: Long, val content: String, val version: Long)

    private data class FilledPage(val lastId: Long, val examined: Int, val filled: Int)

    private companion object {
        const val MAX_ROWS_PER_RUN = 5_000
        const val PAGE_SIZE = 200
    }
}

data class ReviewLanguageBackfillResult(val examined: Int, val filled: Int) {
    val leftUnknown: Int = examined - filled
}
