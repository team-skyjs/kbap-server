package com.kbap.common.domain.report.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
    name = "report",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_report_reporter_target", columnNames = ["reporter_member_id", "target_type", "target_id"]),
    ],
)
class Report(
    @Column(name = "reporter_member_id", nullable = false)
    val reporterMemberId: Long,

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
    @Enumerated(EnumType.STRING)
    @Column(name = "handle_status", nullable = false, length = 20)
    var handleStatus: ReportHandleStatus = ReportHandleStatus.PENDING

    @Enumerated(EnumType.STRING)
    @Column(name = "handle_result", length = 30)
    var handleResult: ReportHandleResult? = null

    @Column(name = "handled_by")
    var handledBy: Long? = null

    @Column(name = "handled_at")
    var handledAt: LocalDateTime? = null

    @Column(name = "handle_note", length = MAX_DETAIL_LENGTH)
    var handleNote: String? = null

    fun isHandled(): Boolean = handleStatus == ReportHandleStatus.HANDLED

    fun handle(result: ReportHandleResult, adminId: Long, note: String?) {
        handleStatus = ReportHandleStatus.HANDLED
        handleResult = result
        handledBy = adminId
        handledAt = LocalDateTime.now()
        handleNote = note?.take(MAX_DETAIL_LENGTH)
    }

    companion object {
        const val MAX_DETAIL_LENGTH = 500
    }
}
