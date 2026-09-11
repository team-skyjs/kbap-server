package com.kbap.api.report

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.report.ReportJpaRepository
import com.kbap.common.domain.report.model.Report
import com.kbap.api.member.MemberService
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
    private val memberService: MemberService,
) {
    @Transactional
    fun createReport(
        reporterMemberId: Long?,
        installationId: String?,
        targetType: ReportTargetType,
        targetId: Long,
        reason: ReportReason,
        detail: String?,
    ) {
        val report = if (reporterMemberId != null) {
            memberService.getMember(reporterMemberId)
            verifyTargetExists(targetType, targetId, reporterMemberId)
            if (reportRepository.existsByReporterMemberIdAndTargetTypeAndTargetId(reporterMemberId, targetType, targetId)) {
                throw BusinessException(ErrorCode.REPORT_DUPLICATED)
            }
            Report.byMember(reporterMemberId, targetType, targetId, reason, detail)
        } else {
            val guestId = installationId?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw BusinessException(ErrorCode.REPORT_INSTALLATION_ID_REQUIRED)
            verifyTargetExists(targetType, targetId, reporterMemberId = null)
            if (reportRepository.existsByReporterInstallationIdAndTargetTypeAndTargetId(guestId, targetType, targetId)) {
                throw BusinessException(ErrorCode.REPORT_DUPLICATED)
            }
            Report.byGuest(guestId, targetType, targetId, reason, detail)
        }
        try {
            reportRepository.save(report)
        } catch (_: DataIntegrityViolationException) {
            throw BusinessException(ErrorCode.REPORT_DUPLICATED)
        }
    }

    private fun verifyTargetExists(targetType: ReportTargetType, targetId: Long, reporterMemberId: Long?) {
        when (targetType) {
            ReportTargetType.REVIEW -> {
                val review = reviewRepository.findById(targetId)
                    .orElseThrow { BusinessException(ErrorCode.REPORT_TARGET_NOT_FOUND) }
                if (reporterMemberId != null && review.isOwnedBy(reporterMemberId)) {
                    throw BusinessException(ErrorCode.REPORT_SELF_TARGET)
                }
            }
        }
    }
}
