package com.kbap.api.scanlog

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.sql.Statement

@Component
class ScanResultLogWriter(private val jdbc: JdbcTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @Transactional
    fun write(row: ScanResultLogRow) {
        try {
            val uploadedImageId = row.imagePath?.let { path ->
                jdbc.query(
                    "SELECT id FROM uploaded_image WHERE object_path = ? AND member_id = ? ORDER BY id DESC LIMIT 1",
                    { rs, _ -> rs.getLong(1) },
                    path,
                    row.memberId,
                ).firstOrNull()
            }
            val keys = GeneratedKeyHolder()
            jdbc.update({ connection ->
                connection.prepareStatement(
                    "INSERT INTO scan_result_log (member_id, uploaded_image_id, image_path, scan_mode, outcome, error_code, ui_kind, vision_kind, " +
                        "app_version, api_version, response_summary, scanned_at, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', NOW(6), NOW(6))",
                    Statement.RETURN_GENERATED_KEYS,
                ).apply {
                    setLong(1, row.memberId)
                    setObject(2, uploadedImageId)
                    setString(3, row.imagePath)
                    setString(4, row.mode.name)
                    setString(5, row.entry.outcome.name)
                    setString(6, row.entry.errorCode)
                    setString(7, row.entry.uiKind.name)
                    setString(8, row.entry.visionKind)
                    setString(9, row.appVersion)
                    setString(10, row.apiVersion)
                    setString(11, row.entry.summary?.let(ScanResultSummaries::toJson))
                    setObject(12, row.scannedAt)
                }
            }, keys)
            val logId = keys.key?.toLong()
            if (logId != null && row.entry.foodLinks.isNotEmpty()) {
                row.entry.foodLinks.forEach { link ->
                    jdbc.update(
                        "INSERT IGNORE INTO scan_result_food (scan_result_log_id, food_id, matched, scanned_name, link_source, status, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, '${ScanResultFoodLinkSource.RECORDED}', 'ACTIVE', NOW(6), NOW(6))",
                        logId,
                        link.foodId,
                        link.matched,
                        link.scannedName,
                    )
                }
            }
        } catch (e: Exception) {
            log.warn("스캔 결과 기록 적재 실패 memberId={}, mode={}", row.memberId, row.mode, e)
        }
    }
}
