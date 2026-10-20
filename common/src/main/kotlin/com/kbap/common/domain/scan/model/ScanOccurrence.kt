package com.kbap.common.domain.scan.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "scan_occurrence")
class ScanOccurrence(
    @Column(name = "operation_id", nullable = false)
    val operationId: Long = 0,

    @Column(name = "photo_row_id", nullable = false)
    val photoRowId: Long = 0,

    @Column(name = "occurrence_key", nullable = false, columnDefinition = "binary(16)")
    val occurrenceKey: ByteArray = ByteArray(16),

    @Column(name = "row_index", nullable = false)
    val rowIndex: Int = 0,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_document", nullable = false)
    val rawDocument: Map<String, Any?> = emptyMap(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "comparison_document", nullable = false)
    val comparisonDocument: Map<String, Any?> = emptyMap(),

    @Column(name = "matched_food_id")
    val matchedFoodId: Long? = null,

    @Column(name = "merged_item_id")
    val mergedItemId: Long? = null,
) : BaseEntity()
