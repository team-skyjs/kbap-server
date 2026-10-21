package com.kbap.common.domain.scan.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "scan_merged_item")
class ScanMergedItem(
    @Column(name = "operation_id", nullable = false)
    val operationId: Long = 0,

    @Column(name = "public_id", nullable = false, columnDefinition = "binary(16)")
    val publicId: ByteArray = ByteArray(16),

    @Column(name = "merge_key", columnDefinition = "binary(32)")
    val mergeKey: ByteArray? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "document", nullable = false)
    val document: Map<String, Any?> = emptyMap(),
) : BaseEntity()
