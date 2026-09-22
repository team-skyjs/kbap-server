package com.kbap.common.domain.report.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.Check

@Entity
@Table(name = "report")
@Check(
    name = "ck_report_reporter_at_least_one",
    constraints = "reporter_member_id is not null or reporter_installation_id is not null",
)
class Report(
    @Column(name = "reporter_member_id")
    val reporterMemberId: Long? = null,

    @Column(name = "reporter_installation_id", length = MAX_INSTALLATION_ID_LENGTH)
    val reporterInstallationId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    val targetType: ReportTargetType,

    @Column(name = "target_id", nullable = false)
    val targetId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val reason: ReportReason,

    @Column(length = MAX_DETAIL_LENGTH)
    val detail: String? = null,
) : BaseEntity() {
    val reporterLabel: String
        get() = reporterMemberId?.let { "member:$it" }
            ?: reporterInstallationId?.let { "게스트(${it.take(GUEST_LABEL_PREFIX)})" }
            ?: "unknown"

    companion object {
        const val MAX_DETAIL_LENGTH = 500
        const val MAX_INSTALLATION_ID_LENGTH = 64
        const val GUEST_LABEL_PREFIX = 8

        private fun requireInstallationId(value: String): String {
            require(value.isNotBlank()) { "reporterInstallationId 는 blank 일 수 없습니다" }
            require(value.length <= MAX_INSTALLATION_ID_LENGTH) {
                "reporterInstallationId 는 최대 ${MAX_INSTALLATION_ID_LENGTH}자입니다"
            }
            return value
        }

        fun byMember(
            reporterMemberId: Long,
            reporterInstallationId: String,
            targetType: ReportTargetType,
            targetId: Long,
            reason: ReportReason,
            detail: String? = null,
        ): Report = Report(
            reporterMemberId = reporterMemberId,
            reporterInstallationId = requireInstallationId(reporterInstallationId),
            targetType = targetType,
            targetId = targetId,
            reason = reason,
            detail = detail,
        )

        fun byGuest(
            reporterInstallationId: String,
            targetType: ReportTargetType,
            targetId: Long,
            reason: ReportReason,
            detail: String? = null,
        ): Report {
            requireInstallationId(reporterInstallationId)
            return Report(
                reporterMemberId = null,
                reporterInstallationId = reporterInstallationId,
                targetType = targetType,
                targetId = targetId,
                reason = reason,
                detail = detail,
            )
        }
    }
}
