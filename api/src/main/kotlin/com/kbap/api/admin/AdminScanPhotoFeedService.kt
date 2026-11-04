package com.kbap.api.admin

import com.kbap.api.scanlog.ScanMode
import com.kbap.api.scanlog.ScanOutcome
import com.kbap.api.scanlog.ScanResultSummaries
import com.kbap.api.scanlog.ScanUiKind
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.image.ScanPhotoFeedRow
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.image.model.UploadPurpose
import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.common.port.llm.ProcessedFoodPhotoKind
import com.kbap.common.util.ImageUrls
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminScanPhotoFeedService(
    private val uploads: UploadedImageJpaRepository,
    private val processedFoods: ProcessedFoodProductJpaRepository,
    private val jdbc: JdbcTemplate,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    @Transactional(readOnly = true)
    fun getScanPhotoFeed(isBot: Boolean, memberId: Long?, excludeMemberIds: List<Long>, cursor: String?, type: ScanPhotoTypeFilter = ScanPhotoTypeFilter.ALL): AdminScanPhotoFeed {
        val beforeId = cursor?.let { it.toLongOrNull() ?: throw BusinessException(ErrorCode.INVALID_CURSOR) } ?: Long.MAX_VALUE
        val rows = uploads.findScanPhotoFeed(
            UploadPurpose.MENU_SCAN.segment,
            UploadPurpose.PACKAGED_SCAN.segment,
            type.includeMenu,
            type.includePackaged,
            isBot,
            memberId,
            excludeMemberIds.ifEmpty { listOf(NO_MEMBER_ID) },
            beforeId,
            PAGE_SIZE + 1,
        )
        val page = rows.take(PAGE_SIZE)
        val hasNext = rows.size > PAGE_SIZE
        val names = processedFoodNamesOf(processedFoods, page.mapNotNull { it.processedFoodId })
        return AdminScanPhotoFeed(
            items = page.map { it.toItem(names) },
            nextCursor = if (hasNext) page.last().rowId.toString() else null,
            hasNext = hasNext,
        )
    }

    @Transactional(readOnly = true)
    fun getScanResultsOrNull(uploadedImageId: Long): AdminScanResults? {
        val records = jdbc.query(
            "SELECT id, member_id, scan_mode, outcome, error_code, ui_kind, vision_kind, app_version, api_version, response_summary, scanned_at, created_at " +
                "FROM scan_result_log WHERE uploaded_image_id = ? AND status = 'ACTIVE' ORDER BY scanned_at DESC, id DESC LIMIT ?",
            { rs, _ ->
                AdminScanResultRecord(
                    id = rs.getLong("id"),
                    memberId = rs.getLong("member_id"),
                    scanMode = ScanMode.valueOf(rs.getString("scan_mode")),
                    outcome = ScanOutcome.valueOf(rs.getString("outcome")),
                    errorCode = rs.getString("error_code"),
                    uiKind = ScanUiKind.valueOf(rs.getString("ui_kind")),
                    visionKind = rs.getString("vision_kind"),
                    appVersion = rs.getString("app_version"),
                    apiVersion = rs.getString("api_version"),
                    responseSummary = ScanResultSummaries.fromJson(rs.getString("response_summary")),
                    scannedAt = rs.getTimestamp("scanned_at").toLocalDateTime(),
                    createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
                )
            },
            uploadedImageId,
            SCAN_RESULTS_LIMIT,
        )
        return records.takeIf { it.isNotEmpty() }?.let(::AdminScanResults)
    }

    private fun ScanPhotoFeedRow.toItem(names: Map<Long, String>): AdminScanPhotoFeedItem {
        val (kind, sourceId) = when {
            orderId != null -> ScanPhotoKind.ORDER to orderId
            operationId != null -> ScanPhotoKind.BATCH to operationId
            else -> ScanPhotoKind.SCAN_ONLY to null
        }
        return AdminScanPhotoFeedItem(
            uploadedImageId = rowId,
            imageUrl = ImageUrls.resolve(imagePublicBaseUrl, path),
            createdAt = createdAt,
            memberId = memberId,
            nickname = nickname,
            countryCode = countryCode,
            isBot = bot,
            memberStatus = memberStatus,
            kind = kind,
            sourceId = sourceId,
            scanType = scanTypeOf(path),
            processedFoodId = processedFoodId,
            processedFoodName = processedFoodId?.let(names::get),
            visionKind = visionKind?.let { kind -> ProcessedFoodPhotoKind.entries.firstOrNull { it.name == kind } },
            scanOutcome = scanOutcome?.let { value -> ScanOutcome.entries.firstOrNull { it.name == value } },
            uiKind = uiKind?.let { value -> ScanUiKind.entries.firstOrNull { it.name == value } },
        )
    }

    companion object {
        const val PAGE_SIZE = 24
        private const val SCAN_RESULTS_LIMIT = 50
        private const val NO_MEMBER_ID = 0L

        fun scanTypeOf(path: String): ScanPhotoType =
            if (UploadPurpose.PACKAGED_SCAN.segment in path) ScanPhotoType.PACKAGED else ScanPhotoType.MENU

        fun processedFoodNamesOf(repository: ProcessedFoodProductJpaRepository, ids: List<Long>): Map<Long, String> =
            if (ids.isEmpty()) emptyMap() else repository.findAllById(ids.distinct()).associate { it.id to it.productName }
    }
}
