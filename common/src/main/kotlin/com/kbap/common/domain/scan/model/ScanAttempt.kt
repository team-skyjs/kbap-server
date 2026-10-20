package com.kbap.common.domain.scan.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "scan_attempt")
class ScanAttempt(
    @Column(name = "operation_id", nullable = false)
    val operationId: Long = 0,

    @Column(name = "member_id", nullable = false)
    val memberId: Long = 0,

    @Column(name = "photo_row_id")
    val photoRowId: Long? = null,

    @Column(name = "unit_key", nullable = false, length = 96)
    val unitKey: String = "",

    @Column(name = "phase", nullable = false, length = 24)
    val phase: String = "",

    @Column(name = "target_manifest_revision", nullable = false)
    val targetManifestRevision: Int = 1,

    @Column(name = "attempt_no", nullable = false)
    val attemptNo: Int = 1,

    @Column(name = "claim_token", nullable = false, columnDefinition = "binary(16)")
    val claimToken: ByteArray = ByteArray(16),

    @Column(name = "reservation_epoch", nullable = false)
    val reservationEpoch: Long = 0,

    @Column(name = "state", nullable = false, length = 24)
    var state: String = "",

    @Column(name = "started_at", nullable = false)
    val startedAt: LocalDateTime = LocalDateTime.MIN,

    @Column(name = "deadline_at", nullable = false)
    val deadlineAt: LocalDateTime = LocalDateTime.MIN,

    @Column(name = "lease_until", nullable = false)
    val leaseUntil: LocalDateTime = LocalDateTime.MIN,

    @Column(name = "finished_at")
    var finishedAt: LocalDateTime? = null,

    @Column(name = "failure_kind", length = 64)
    var failureKind: String? = null,
) : BaseEntity() {
    fun finish(state: String, failureKind: String?, at: LocalDateTime) {
        this.state = state
        this.failureKind = failureKind
        finishedAt = at
    }
}
