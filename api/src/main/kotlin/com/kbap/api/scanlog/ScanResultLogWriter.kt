package com.kbap.api.scanlog

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

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
            jdbc.update(
                "INSERT INTO scan_result_log (member_id, uploaded_image_id, image_path, scan_mode, outcome, error_code, ui_kind, vision_kind, " +
                    "app_version, api_version, response_summary, scanned_at, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', NOW(6), NOW(6))",
                row.memberId,
                uploadedImageId,
                row.imagePath,
                row.mode.name,
                row.entry.outcome.name,
                row.entry.errorCode,
                row.entry.uiKind.name,
                row.entry.visionKind,
                row.appVersion,
                row.apiVersion,
                row.entry.summary?.let(ScanResultSummaries::toJson),
                row.scannedAt,
            )
        } catch (e: Exception) {
            log.warn("스캔 결과 기록 적재 실패 memberId={}, mode={}", row.memberId, row.mode, e)
        }
    }
}
