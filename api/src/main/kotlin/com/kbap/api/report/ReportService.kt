package com.kbap.api.report

import com.kbap.api.core.ApiHeaders
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.report.ReportJpaRepository
import com.kbap.common.domain.report.model.Report
import com.kbap.api.member.MemberService
import com.kbap.common.domain.report.model.ReportReason
import com.kbap.common.domain.report.model.ReportTargetType
import com.kbap.common.domain.review.ReviewJpaRepository
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
        val installation = requireInstallationId(installationId)
        reporterMemberId?.let { memberService.getMember(it) }
        verifyTargetExists(targetType, targetId, reporterMemberId)

        val report = if (reporterMemberId != null) {
            Report.byMember(reporterMemberId, installation, targetType, targetId, reason, detail)
        } else {
            Report.byGuest(installation, targetType, targetId, reason, detail)
        }
        reportRepository.save(report)
    }

    private fun requireInstallationId(raw: String?): String =
        raw?.let(ApiHeaders::validInstallationId)
            ?: throw BusinessException(ErrorCode.REPORT_INSTALLATION_ID_REQUIRED)

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
