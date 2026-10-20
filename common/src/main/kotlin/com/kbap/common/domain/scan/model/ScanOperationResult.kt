package com.kbap.common.domain.scan.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "scan_operation_result")
class ScanOperationResult(
    @Column(name = "operation_id", nullable = false)
    val operationId: Long = 0,

    @Column(name = "photo_row_id", nullable = false)
    val photoRowId: Long = 0,

    @Column(name = "committed_attempt_id", nullable = false)
    val committedAttemptId: Long = 0,

    @Column(name = "committed_attempt_no", nullable = false)
    val committedAttemptNo: Int = 1,

    @Column(name = "target_manifest_revision", nullable = false)
    val targetManifestRevision: Int = 1,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_document", nullable = false)
    val resultDocument: Map<String, Any?> = emptyMap(),
) : BaseEntity()
