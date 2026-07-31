package com.kbap.common.domain.report.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

// UNIQUE 를 엔티티에도 선언한다 — :common 영속 테스트는 Flyway 없이 엔티티로 스키마를 생성한다.
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
    companion object {
        const val MAX_DETAIL_LENGTH = 500
    }
}
