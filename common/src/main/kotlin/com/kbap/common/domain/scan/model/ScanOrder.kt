package com.kbap.common.domain.scan.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "scan_order")
class ScanOrder(
    @Column(name = "operation_id", nullable = false)
    val operationId: Long = 0,

    @Column(name = "order_id", nullable = false)
    val orderId: Long = 0,

    @Column(name = "request_key", nullable = false, columnDefinition = "binary(16)")
    val requestKey: ByteArray = ByteArray(16),

    @Column(name = "request_hash", nullable = false, columnDefinition = "binary(32)")
    val requestHash: ByteArray = ByteArray(32),

    @Column(name = "result_revision", nullable = false)
    val resultRevision: Long = 1,
) : BaseEntity()
