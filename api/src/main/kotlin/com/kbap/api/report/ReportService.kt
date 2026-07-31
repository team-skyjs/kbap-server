package com.kbap.api.report

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
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
) {
    @Transactional
    fun createReport(
        reporterMemberId: Long,
        targetType: ReportTargetType,
        targetId: Long,
        reason: ReportReason,
        detail: String?,
    ) {
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
            // 동시 중복 신고 경합 — UNIQUE 제약이 최종 방어(research R2)
            throw BusinessException(ErrorCode.REPORT_DUPLICATED)
        }
    }

    private fun verifyReportable(reporterMemberId: Long, targetType: ReportTargetType, targetId: Long) {
        when (targetType) {
            ReportTargetType.REVIEW -> {
                val review = reviewRepository.findById(targetId)
                    .orElseThrow { BusinessException(ErrorCode.REPORT_TARGET_NOT_FOUND) }
                if (review.isOwnedBy(reporterMemberId)) {
                    throw BusinessException(ErrorCode.REPORT_SELF_TARGET)
                }
            }
        }
    }
}
