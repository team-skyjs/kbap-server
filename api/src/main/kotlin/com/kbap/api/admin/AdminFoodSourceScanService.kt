package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.scanlog.ScanResultFoodLinkSource
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.util.ImageUrls
import com.kbap.common.util.KoreanMenuNameNormalizer
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class AdminFoodSourceScanService(
    private val jdbc: JdbcTemplate,
    private val foodRepository: FoodJpaRepository,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    private val mapper = jacksonObjectMapper()

    @Transactional(readOnly = true)
    fun getSourceScans(foodId: Long, limit: Int): AdminFoodSourceScans {
        val food = foodRepository.findAnyById(foodId) ?: throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
        val items = jdbc.query(
            "SELECT l.id, l.member_id, l.uploaded_image_id, l.image_path, l.scan_mode, l.vision_kind, l.scanned_at, f.scanned_name, f.matched, f.link_source " +
                "FROM scan_result_food f JOIN scan_result_log l ON l.id = f.scan_result_log_id AND l.status = 'ACTIVE' " +
                "WHERE f.food_id = ? AND f.status = 'ACTIVE' ORDER BY l.scanned_at DESC, l.id DESC LIMIT ?",
            { rs, _ ->
                AdminFoodSourceScan(
                    scanResultLogId = rs.getLong("id"),
                    memberId = rs.getLong("member_id"),
                    uploadedImageId = rs.getObject("uploaded_image_id")?.let { (it as Number).toLong() },
                    imageUrl = rs.getString("image_path")?.let { ImageUrls.resolve(imagePublicBaseUrl, it) },
                    scanMode = rs.getString("scan_mode"),
                    visionKind = rs.getString("vision_kind"),
                    scannedAt = rs.getTimestamp("scanned_at").toLocalDateTime(),
                    scannedName = rs.getString("scanned_name"),
                    matched = rs.getBoolean("matched"),
                    linkSource = ScanResultFoodLinkSource.valueOf(rs.getString("link_source")),
                )
            },
            foodId,
            limit,
        )
        val recordedSince = jdbc.query("SELECT MIN(scanned_at) FROM scan_result_log", { rs, _ -> rs.getTimestamp(1)?.toLocalDateTime() }).firstOrNull()
        return AdminFoodSourceScans(foodId = foodId, foodCreatedAt = food.createdAt, sourceRecordedSince = recordedSince, items = items)
    }

    @Transactional
    fun backfillByName(afterLogId: Long, batchSize: Int): AdminSourceScanBackfillResult {
        val rows = jdbc.query(
            "SELECT id, response_summary FROM scan_result_log WHERE id > ? AND status = 'ACTIVE' AND scan_mode IN ('MENU_V1', 'MENU_V2') AND response_summary IS NOT NULL ORDER BY id LIMIT ?",
            { rs, _ -> rs.getLong("id") to rs.getString("response_summary") },
            afterLogId,
            batchSize,
        )
        val links = rows.flatMap { (logId, summary) -> itemsOf(summary).map { logId to it } }
        val foodIdByKey = links.filter { (_, item) -> item.foodId == null }
            .map { (_, item) -> KoreanMenuNameNormalizer.matchKey(item.name) }.filter { it.isNotBlank() }
            .toSet().takeIf { it.isNotEmpty() }?.let { keys -> foodRepository.findByKoreanNameIn(keys).associate { it.koreanName to it.id } }.orEmpty()
        val existingIds = links.mapNotNull { (_, item) -> item.foodId }.toSet().takeIf { it.isNotEmpty() }
            ?.let { ids -> foodRepository.findAllById(ids).map { it.id }.toSet() }.orEmpty()
        var linked = 0
        links.forEach { (logId, item) ->
            val (foodId, source) = item.foodId?.takeIf { it in existingIds }?.let { it to ScanResultFoodLinkSource.RECORDED }
                ?: foodIdByKey[KoreanMenuNameNormalizer.matchKey(item.name)]?.let { it to ScanResultFoodLinkSource.NAME_BACKFILL }
                ?: return@forEach
            linked += jdbc.update(
                "INSERT IGNORE INTO scan_result_food (scan_result_log_id, food_id, matched, scanned_name, link_source, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW(6), NOW(6))",
                logId,
                foodId,
                item.matched,
                item.name.take(MAX_NAME_LENGTH),
                source.name,
            )
        }
        val last = rows.lastOrNull()?.first
        return AdminSourceScanBackfillResult(scannedLogs = rows.size, linked = linked, nextAfterLogId = last?.takeIf { rows.size == batchSize })
    }

    private fun itemsOf(summary: String): List<SummaryItem> {
        val node = runCatching { mapper.readTree(summary) }.getOrNull() ?: return emptyList()
        return listOf("items", "photoOnly").flatMap { key ->
            node.path(key).mapNotNull { item ->
                val name = item.path("name").takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SummaryItem(name, item.path("matched").asBoolean(false), item.path("foodId").takeIf { it.isNumber }?.asLong())
            }
        }
    }

    private data class SummaryItem(val name: String, val matched: Boolean, val foodId: Long?)

    private companion object {
        const val MAX_NAME_LENGTH = 100
    }
}

@Schema(description = "음식의 출처 스캔(최근 순)")
data class AdminFoodSourceScans(
    val foodId: Long,
    @field:Schema(description = "음식 행 생성 시각 — sourceRecordedSince 보다 이르고 items 가 비면 '출처 기록 없음(기록 시작 전)'")
    val foodCreatedAt: LocalDateTime,
    @field:Schema(description = "이 환경 스캔 결과 기록의 첫 시각(없으면 null)", nullable = true)
    val sourceRecordedSince: LocalDateTime?,
    val items: List<AdminFoodSourceScan>,
)

@Schema(description = "출처 스캔 한 건")
data class AdminFoodSourceScan(
    val scanResultLogId: Long,
    @field:Schema(description = "스캔한 회원 id(본인 계정 표시용)")
    val memberId: Long,
    @field:Schema(description = "어드민 스캔 사진 상세 링크용 — 업로드 기록을 못 찾은 스캔은 null", nullable = true)
    val uploadedImageId: Long?,
    @field:Schema(description = "스캔 사진 URL(공개 CDN)", nullable = true)
    val imageUrl: String?,
    @field:Schema(description = "MENU_V1·MENU_V2·PFOOD_NAME·PFOOD_LABEL·PFOOD_VISION")
    val scanMode: String,
    val visionKind: String?,
    val scannedAt: LocalDateTime,
    @field:Schema(description = "그 사진에서 읽힌 원문 메뉴 이름", nullable = true)
    val scannedName: String?,
    @field:Schema(description = "스캔 당시 정식 음식으로 매칭됐는지")
    val matched: Boolean,
    @field:Schema(description = "RECORDED(스캔 시점 기록·요약의 음식 id) 또는 NAME_BACKFILL(지난 기록을 이름 정규화 일치로 사후 연결)")
    val linkSource: ScanResultFoodLinkSource,
)

@Schema(description = "이름 기준 출처 연결 백필 결과 — nextAfterLogId 가 있으면 그 값으로 다시 부른다")
data class AdminSourceScanBackfillResult(
    val scannedLogs: Int,
    @field:Schema(description = "새로 넣은 연결 행 수(이미 있던 건 건너뜀 — 멱등)")
    val linked: Int,
    val nextAfterLogId: Long?,
)
