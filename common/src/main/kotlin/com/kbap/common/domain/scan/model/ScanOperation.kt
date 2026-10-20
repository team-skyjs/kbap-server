package com.kbap.common.domain.scan.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

@Entity
@Table(name = "scan_operation")
class ScanOperation(
    @Column(name = "member_id", nullable = false)
    val memberId: Long = 0,

    @Column(name = "key_namespace", nullable = false, length = 24)
    val keyNamespace: String = "",

    @Column(name = "request_key", nullable = false, columnDefinition = "binary(16)")
    val requestKey: ByteArray = ByteArray(16),

    @Column(name = "manifest_hash", nullable = false, columnDefinition = "binary(32)")
    val manifestHash: ByteArray = ByteArray(32),

    @Column(name = "mode", nullable = false, length = 24)
    val mode: String = "",

    @Column(name = "lifecycle", nullable = false, length = 24)
    var lifecycle: String = "",

    @Column(name = "initial_language", nullable = false, length = 16)
    val initialLanguage: String = "",

    @Column(name = "fx_state", nullable = false, length = 24)
    val fxState: String = "",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_document", nullable = false)
    var requestDocument: Map<String, Any?> = emptyMap(),

    @Column(name = "limits_version", nullable = false, length = 32)
    val limitsVersion: String = "",

    @Column(name = "usage_recorded_at")
    var usageRecordedAt: LocalDateTime? = null,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: LocalDateTime = LocalDateTime.MIN,

    @Column(name = "result_expires_at", nullable = false)
    val resultExpiresAt: LocalDateTime = LocalDateTime.MIN,

    @Column(name = "key_retire_after", nullable = false)
    val keyRetireAfter: LocalDateTime = LocalDateTime.MIN,
) : BaseEntity()
