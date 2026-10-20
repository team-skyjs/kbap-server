package com.kbap.common.domain.scan.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "scan_usage")
class ScanUsage(
    @Column(name = "operation_id", nullable = false)
    val operationId: Long = 0,

    @Column(name = "recorded_at", nullable = false)
    val recordedAt: LocalDateTime = LocalDateTime.MIN,

    @Column(name = "entitlement_at_commit", nullable = false, length = 24)
    val entitlementAtCommit: String = "",
) : BaseEntity()
