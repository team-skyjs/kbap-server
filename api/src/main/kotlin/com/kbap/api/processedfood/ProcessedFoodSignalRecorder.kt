package com.kbap.api.processedfood

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

enum class ProcessedFoodSignalType { VIEW, SCAN_MATCH }

@Component
class ProcessedFoodSignalRecorder(private val jdbc: JdbcTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun record(processedFoodId: Long, memberId: Long, type: ProcessedFoodSignalType) {
        try {
            jdbc.update(
                "INSERT IGNORE INTO processed_food_signal (processed_food_id, member_id, signal_type, signal_date, status, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, 'ACTIVE', NOW(6), NOW(6))",
                processedFoodId, memberId, type.name, LocalDate.now(SEOUL),
            )
        } catch (e: RuntimeException) {
            log.warn("가공식품 신호 적재 실패 — 응답은 그대로 둔다 processedFoodId={}, type={}", processedFoodId, type, e)
        }
    }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
