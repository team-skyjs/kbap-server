package com.kbap.api.review

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Component
class ReviewLanguageBackfill(
    private val jdbcTemplate: JdbcTemplate,
    private val detector: ReviewLanguageDetector,
    transactionManager: PlatformTransactionManager,
    @Value("\${kbap.review.language-backfill.enabled:true}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val pageTransaction = TransactionTemplate(transactionManager)

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
            val page = pageTransaction.execute { fillPageAfter(lastId) } ?: break
            if (page.examined == 0) break
            lastId = page.lastId
            examined += page.examined
            filled += page.filled
        }
        return ReviewLanguageBackfillResult(examined, filled)
    }

    private fun fillPageAfter(lastId: Long): FilledPage {
        val rows = jdbcTemplate.query(
            "SELECT id, content FROM food_review " +
                "WHERE id > ? AND language IS NULL AND content IS NOT NULL AND status = 'ACTIVE' ORDER BY id LIMIT ?",
            { rs, _ -> rs.getLong("id") to rs.getString("content") },
            lastId, PAGE_SIZE,
        )
        val filled = rows.sumOf { (id, content) ->
            detector.detect(content)?.let { language ->
                jdbcTemplate.update("UPDATE food_review SET language = ?, updated_at = updated_at WHERE id = ? AND language IS NULL", language.code, id)
            } ?: 0
        }
        return FilledPage(lastId = rows.lastOrNull()?.first ?: lastId, examined = rows.size, filled = filled)
    }

    private data class FilledPage(val lastId: Long, val examined: Int, val filled: Int)

    private companion object {
        const val MAX_ROWS_PER_RUN = 5_000
        const val PAGE_SIZE = 200
    }
}

data class ReviewLanguageBackfillResult(val examined: Int, val filled: Int) {
    val leftUnknown: Int = examined - filled
}
