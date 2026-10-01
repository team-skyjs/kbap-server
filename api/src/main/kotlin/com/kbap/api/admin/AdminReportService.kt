package com.kbap.api.admin

import com.kbap.api.review.ReviewService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.report.ReportJpaRepository
import com.kbap.common.domain.report.model.Report
import com.kbap.common.domain.report.model.ReportHandleResult
import com.kbap.common.domain.report.model.ReportHandleStatus
import com.kbap.common.domain.report.model.ReportTargetType
import com.kbap.common.domain.review.ReviewJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class AdminReportService(
    private val reportRepository: ReportJpaRepository,
    private val reviewRepository: ReviewJpaRepository,
    private val reviewService: ReviewService,
) {
    @Transactional(readOnly = true)
    fun getReportPage(handleStatus: ReportHandleStatus, targetType: ReportTargetType?, page: Int, size: Int): AdminReportPageResult {
        val pending = handleStatus == ReportHandleStatus.PENDING
        val summaries = reportRepository.findTargetSummaries(targetType?.name, pending, size, page.toLong() * size)
        val total = reportRepository.countTargetSummaries(targetType?.name, pending)
        val reportsByTarget = summaries.groupBy { ReportTargetType.valueOf(it.targetType) }
            .flatMap { (type, rows) -> reportRepository.findByTargetTypeAndTargetIdInOrderByIdDesc(type, rows.map { it.targetId }) }
            .groupBy { it.targetType to it.targetId }
        val reviews = summaries.filter { it.targetType == ReportTargetType.REVIEW.name }.map { it.targetId }
            .takeIf { it.isNotEmpty() }
            ?.let { ids -> reviewRepository.findAllAnyStatusByIdIn(ids).associateBy { it.id } }
            .orEmpty()
        return AdminReportPageResult(
            groups = summaries.map { summary ->
                val type = ReportTargetType.valueOf(summary.targetType)
                val items = reportsByTarget[type to summary.targetId].orEmpty()
                val review = reviews[summary.targetId].takeIf { type == ReportTargetType.REVIEW }
                AdminReportPageResult.Group(
                    target = AdminReportPageResult.Target(
                        type = type,
                        id = summary.targetId,
                        authorMemberId = review?.memberId,
                        contentPreview = review?.content?.take(CONTENT_PREVIEW_LENGTH),
                        exists = review?.isActive() == true,
                    ),
                    pendingCount = summary.pendingCount.toInt(),
                    totalCount = summary.totalCount.toInt(),
                    reporterCount = summary.reporterCount.toInt(),
                    latest = items.first(),
                    items = items,
                )
            },
            page = page,
            size = size,
            totalCount = total,
        )
    }

    @Transactional
    fun handleReport(reportId: Long, result: ReportHandleResult, note: String?, adminAccountId: Long): AdminReportHandleResult {
        val report = reportRepository.findById(reportId).orElseThrow { BusinessException(ErrorCode.REPORT_NOT_FOUND) }
        return handle(report.targetType, report.targetId, result, note, adminAccountId, requiredReportId = reportId)
    }

    @Transactional
    fun handleTarget(
        targetType: ReportTargetType,
        targetId: Long,
        result: ReportHandleResult,
        note: String?,
        adminAccountId: Long,
    ): AdminReportHandleResult = handle(targetType, targetId, result, note, adminAccountId)

    private fun handle(
        targetType: ReportTargetType,
        targetId: Long,
        result: ReportHandleResult,
        note: String?,
        adminAccountId: Long,
        requiredReportId: Long? = null,
    ): AdminReportHandleResult {
        val contentDeleted = result == ReportHandleResult.CONTENT_DELETED && deleteContent(targetType, targetId)
        val pending = reportRepository.findPendingOfTargetForUpdate(targetType.name, targetId)
        if (pending.isEmpty() || (requiredReportId != null && pending.none { it.id == requiredReportId })) {
            throw BusinessException(ErrorCode.REPORT_ALREADY_HANDLED)
        }
        val now = LocalDateTime.now()
        pending.forEach { it.handle(result, adminAccountId, now, note) }
        return AdminReportHandleResult(targetType, targetId, result, pending.size, contentDeleted)
    }

    private fun deleteContent(targetType: ReportTargetType, targetId: Long): Boolean =
        when (targetType) {
            ReportTargetType.REVIEW -> {
                val review = reviewRepository.findAnyByIdForUpdate(targetId)
                if (review == null || !review.isActive()) {
                    false
                } else {
                    reviewService.deleteForModeration(review)
                    true
                }
            }
        }

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
        private const val CONTENT_PREVIEW_LENGTH = 100
    }
}

data class AdminReportPageResult(
    val groups: List<Group>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
) {
    data class Group(
        val target: Target,
        val pendingCount: Int,
        val totalCount: Int,
        val reporterCount: Int,
        val latest: Report,
        val items: List<Report>,
    )

    data class Target(
        val type: ReportTargetType,
        val id: Long,
        val authorMemberId: Long?,
        val contentPreview: String?,
        val exists: Boolean,
    )
}

data class AdminReportHandleResult(
    val targetType: ReportTargetType,
    val targetId: Long,
    val result: ReportHandleResult,
    val handledCount: Int,
    val contentDeleted: Boolean,
)
