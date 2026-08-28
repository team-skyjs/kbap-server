package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import com.kbap.common.domain.community.CommentJpaRepository
import com.kbap.common.domain.community.PostingJpaRepository
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.report.ReportJpaRepository
import com.kbap.common.domain.report.model.Report
import com.kbap.common.domain.report.model.ReportHandleResult
import com.kbap.common.domain.report.model.ReportHandleStatus
import com.kbap.common.domain.report.model.ReportReason
import com.kbap.common.domain.report.model.ReportTargetType
import com.kbap.common.domain.review.ReviewJpaRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminReportService(
    private val reportRepository: ReportJpaRepository,
    private val reviewRepository: ReviewJpaRepository,
    private val postingRepository: PostingJpaRepository,
    private val commentRepository: CommentJpaRepository,
    private val memberRepository: MemberJpaRepository,
    private val adminReviewService: AdminReviewService,
    private val adminCommunityService: AdminCommunityService,
    private val adminMemberService: AdminMemberService,
    private val auditRecorder: AdminAuditRecorder,
) {
    private data class TargetInfo(val authorMemberId: Long, val contentPreview: String?)

    @Transactional(readOnly = true)
    fun getReportPage(
        handleStatus: ReportHandleStatus?,
        reason: ReportReason?,
        targetType: ReportTargetType?,
        page: Int,
        size: Int,
    ): AdminReportPageResponse {
        val result = reportRepository.findAdminPage(handleStatus, reason, targetType, PageRequest.of(page - 1, size))
        return AdminReportPageResponse(
            items = toResponses(result.content),
            page = page,
            size = size,
            totalCount = result.totalElements,
            totalPages = totalPagesOf(result.totalElements, size),
        )
    }

    @Transactional
    fun handle(adminId: Long, reportId: Long, result: ReportHandleResult, note: String?): AdminReportHandleResponse {
        val report = reportRepository.findById(reportId).orElseThrow { BusinessException(ErrorCode.REPORT_NOT_FOUND) }
        if (report.isHandled()) throw BusinessException(ErrorCode.REPORT_ALREADY_HANDLED)
        val trimmedNote = note?.trim()?.takeIf { it.isNotEmpty() }
        when (result) {
            ReportHandleResult.DISMISSED -> Unit
            ReportHandleResult.CONTENT_DELETED -> deleteTarget(adminId, report.targetType, report.targetId)
            ReportHandleResult.MEMBER_SUSPENDED -> {
                if (trimmedNote == null) throw BusinessException(ErrorCode.INVALID_REQUEST)
                val author = targetInfo(report.targetType, report.targetId)?.authorMemberId
                    ?: throw BusinessException(ErrorCode.REPORT_TARGET_NOT_FOUND)
                adminMemberService.changeStatus(adminId, author, MemberStatus.SUSPENDED, trimmedNote)
            }
        }
        val siblings = reportRepository.findByTargetTypeAndTargetIdAndHandleStatus(report.targetType, report.targetId, ReportHandleStatus.PENDING)
        siblings.forEach { it.handle(result, adminId, trimmedNote) }
        auditRecorder.record(
            adminId, AdminAuditAction.REPORT_HANDLE, AdminAuditTargetType.REPORT, report.id,
            mapOf("handleStatus" to ReportHandleStatus.PENDING.name),
            mapOf("handleStatus" to ReportHandleStatus.HANDLED.name, "result" to result.name, "handledReportIds" to siblings.map { it.id }),
            note = trimmedNote,
        )
        val handled = siblings.firstOrNull { it.id == report.id } ?: report
        return AdminReportHandleResponse(report = toResponses(listOf(handled)).single(), handledReportIds = siblings.map { it.id })
    }

    private fun deleteTarget(adminId: Long, targetType: ReportTargetType, targetId: Long) {
        when (targetType) {
            ReportTargetType.REVIEW -> if (reviewRepository.existsById(targetId)) adminReviewService.deleteReview(adminId, targetId)
            ReportTargetType.POST -> if (postingRepository.existsById(targetId)) adminCommunityService.deletePost(adminId, targetId)
            ReportTargetType.COMMENT -> if (commentRepository.existsById(targetId)) adminCommunityService.deleteComment(adminId, targetId)
        }
    }

    private fun targetInfo(targetType: ReportTargetType, targetId: Long): TargetInfo? = when (targetType) {
        ReportTargetType.REVIEW -> reviewRepository.findById(targetId).orElse(null)?.let { TargetInfo(it.memberId, it.content) }
        ReportTargetType.POST -> postingRepository.findById(targetId).orElse(null)?.let { TargetInfo(it.memberId, it.content) }
        ReportTargetType.COMMENT -> commentRepository.findById(targetId).orElse(null)?.let { TargetInfo(it.memberId, it.content) }
    }

    private fun toResponses(reports: List<Report>): List<AdminReportResponse> {
        if (reports.isEmpty()) return emptyList()
        val nicknames = memberRepository.findAllById(reports.map { it.reporterMemberId }.toSet()).associate { it.id to it.nickname }
        val counts = reports.groupBy { it.targetType }.flatMap { (type, group) ->
            reportRepository.countByTarget(type, group.map { it.targetId }.toSet()).map { (type to it.targetId) to it.reportCount }
        }.toMap()
        val targets = reports.map { it.targetType to it.targetId }.distinct().associateWith { (type, id) -> targetInfo(type, id) }
        return reports.map { report ->
            val key = report.targetType to report.targetId
            val info = targets[key]
            AdminReportResponse(
                id = report.id,
                reporterMemberId = report.reporterMemberId,
                reporterNickname = nicknames[report.reporterMemberId],
                target = AdminReportTargetResponse(
                    type = report.targetType,
                    id = report.targetId,
                    authorMemberId = info?.authorMemberId,
                    contentPreview = info?.contentPreview?.take(PREVIEW_LENGTH),
                    exists = info != null,
                    reportCount = counts[key] ?: 0L,
                ),
                reason = report.reason,
                detail = report.detail,
                handleStatus = report.handleStatus,
                handleResult = report.handleResult,
                handledBy = report.handledBy,
                handledAt = report.handledAt,
                handleNote = report.handleNote,
                createdAt = report.createdAt,
            )
        }
    }

    companion object {
        const val PREVIEW_LENGTH = 80
    }
}
