package com.kbap.common.domain.report.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "report",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_report_reporter_target", columnNames = ["reporter_member_id", "target_type", "target_id"]),
        UniqueConstraint(
            name = "uk_report_reporter_installation_target",
            columnNames = ["reporter_installation_id", "target_type", "target_id"],
        ),
    ],
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

        fun byMember(
            reporterMemberId: Long,
            targetType: ReportTargetType,
            targetId: Long,
            reason: ReportReason,
            detail: String? = null,
        ): Report = Report(
            reporterMemberId = reporterMemberId,
            reporterInstallationId = null,
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
            require(reporterInstallationId.isNotBlank()) { "reporterInstallationId 는 blank 일 수 없습니다" }
            require(reporterInstallationId.length <= MAX_INSTALLATION_ID_LENGTH) {
                "reporterInstallationId 는 최대 ${MAX_INSTALLATION_ID_LENGTH}자입니다"
            }
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
