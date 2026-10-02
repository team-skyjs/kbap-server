package com.kbap.api.review

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager

@Component
class ReviewLanguageBackfill(
    private val jdbcTemplate: JdbcTemplate,
    private val detector: ReviewLanguageDetector,
    transactionManager: PlatformTransactionManager,
    @Value("\${kbap.review.language-backfill.enabled:true}") private val enabled: Boolean,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady(): Thread? = null

    fun backfill(): ReviewLanguageBackfillResult = ReviewLanguageBackfillResult(examined = 0, filled = 0)
}

data class ReviewLanguageBackfillResult(val examined: Int, val filled: Int) {
    val leftUnknown: Int = examined - filled
}
