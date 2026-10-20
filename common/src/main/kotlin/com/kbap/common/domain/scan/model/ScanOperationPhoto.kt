package com.kbap.common.domain.scan.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

@Entity
@Table(name = "scan_operation_photo")
class ScanOperationPhoto(
    @Column(name = "operation_id", nullable = false)
    val operationId: Long = 0,

    @Column(name = "photo_id", nullable = false, columnDefinition = "binary(16)")
    val photoId: ByteArray = ByteArray(16),

    @Column(name = "selection_index", nullable = false)
    val selectionIndex: Int = 0,

    @Column(name = "uploaded_image_id", nullable = false)
    val uploadedImageId: Long = 0,

    @Column(name = "object_key", nullable = false, length = 512)
    val objectKey: String = "",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_fingerprint", nullable = false)
    val sourceFingerprint: Map<String, Any?> = emptyMap(),

    @Column(name = "byte_size", nullable = false)
    val byteSize: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_state", nullable = false, length = 24)
    var executionState: ScanPhotoState = ScanPhotoState.PENDING,

    @Column(name = "attempt_no", nullable = false)
    var attemptNo: Int = 0,

    @Column(name = "lease_token", columnDefinition = "binary(16)")
    var leaseToken: ByteArray? = null,

    @Column(name = "lease_until")
    var leaseUntil: LocalDateTime? = null,

    @Column(name = "error_reason", length = 64)
    var errorReason: String? = null,
) : BaseEntity() {
    fun isRunnableAt(now: LocalDateTime): Boolean = when (executionState) {
        ScanPhotoState.PENDING -> true
        ScanPhotoState.FAILED -> errorReason != QUOTA_EXHAUSTED
        ScanPhotoState.RUNNING -> leaseUntil?.isBefore(now) ?: true
        ScanPhotoState.SUCCEEDED, ScanPhotoState.CANCELLED -> false
    }

    fun holdsLease(token: ByteArray): Boolean = executionState == ScanPhotoState.RUNNING && leaseToken?.contentEquals(token) == true

    fun claim(token: ByteArray, until: LocalDateTime) {
        attemptNo += 1
        executionState = ScanPhotoState.RUNNING
        leaseToken = token
        leaseUntil = until
        errorReason = null
    }

    fun succeed() = settle(ScanPhotoState.SUCCEEDED, null)

    fun fail(reason: String) = settle(ScanPhotoState.FAILED, reason)

    fun cancel() {
        if (executionState == ScanPhotoState.PENDING || executionState == ScanPhotoState.RUNNING) settle(ScanPhotoState.CANCELLED, null)
    }

    private fun settle(state: ScanPhotoState, reason: String?) {
        executionState = state
        errorReason = reason
        leaseToken = null
        leaseUntil = null
    }

    companion object {
        const val QUOTA_EXHAUSTED = "QUOTA_EXHAUSTED"
        const val LEASE_EXPIRED = "LEASE_EXPIRED"
    }
}
