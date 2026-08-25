package com.kbap.api.report

import com.kbap.api.member.MemberService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.community.CommentJpaRepository
import com.kbap.common.domain.community.PostingJpaRepository
import com.kbap.common.domain.report.ReportJpaRepository
import com.kbap.common.domain.report.model.Report
import com.kbap.common.domain.report.model.ReportReason
import com.kbap.common.domain.report.model.ReportTargetType
import com.kbap.common.domain.review.ReviewJpaRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReportService(
    private val reportRepository: ReportJpaRepository,
    private val reviewRepository: ReviewJpaRepository,
    private val postingRepository: PostingJpaRepository,
    private val commentRepository: CommentJpaRepository,
    private val memberService: MemberService,
) {
    @Transactional
    fun createReport(
        reporterMemberId: Long,
        targetType: ReportTargetType,
        targetId: Long,
        reason: ReportReason,
        detail: String?,
    ) {
        memberService.getMember(reporterMemberId)
        verifyReportable(reporterMemberId, targetType, targetId)
        if (reportRepository.existsByReporterMemberIdAndTargetTypeAndTargetId(reporterMemberId, targetType, targetId)) {
            throw BusinessException(ErrorCode.REPORT_DUPLICATED)
        }
        try {
            reportRepository.save(
                Report(
                    reporterMemberId = reporterMemberId,
                    targetType = targetType,
                    targetId = targetId,
                    reason = reason,
                    detail = detail,
                ),
            )
        } catch (_: DataIntegrityViolationException) {
            throw BusinessException(ErrorCode.REPORT_DUPLICATED)
        }
    }

    private fun verifyReportable(reporterMemberId: Long, targetType: ReportTargetType, targetId: Long) {
        val ownedByReporter = when (targetType) {
            ReportTargetType.REVIEW -> reviewRepository.findById(targetId).orElse(null)?.isOwnedBy(reporterMemberId)
            ReportTargetType.POST -> postingRepository.findById(targetId).orElse(null)?.isOwnedBy(reporterMemberId)
            ReportTargetType.COMMENT -> commentRepository.findById(targetId).orElse(null)?.isOwnedBy(reporterMemberId)
        } ?: throw BusinessException(ErrorCode.REPORT_TARGET_NOT_FOUND)
        if (ownedByReporter) throw BusinessException(ErrorCode.REPORT_SELF_TARGET)
    }
}
