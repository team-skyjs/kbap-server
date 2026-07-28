package com.kbap.common.domain.food.model

import com.kbap.common.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
    name = "image_batch",
    uniqueConstraints = [UniqueConstraint(name = "uq_image_batch_openai_batch_id", columnNames = ["openai_batch_id"])],
    indexes = [Index(name = "idx_image_batch_status", columnList = "batch_status")],
)
class ImageBatch(
    @Column(name = "openai_batch_id", length = 100)
    var openaiBatchId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "batch_status", nullable = false, columnDefinition = "ENUM('SUBMITTING','SUBMITTED','COLLECTED','FAILED')")
    var batchStatus: ImageBatchStatus = ImageBatchStatus.SUBMITTING,

    @Column(name = "prompt_version", nullable = false, length = 20)
    var promptVersion: String = "",

    @Column(name = "model", nullable = false, length = 50)
    var model: String = "",

    @Column(name = "submitted_at", nullable = false)
    var submittedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "collected_at")
    var collectedAt: LocalDateTime? = null,
) : BaseEntity() {
    fun markSubmitted(openaiBatchId: String) {
        require(openaiBatchId.isNotBlank()) { "openaiBatchId 는 blank 일 수 없습니다" }
        this.openaiBatchId = openaiBatchId
        this.batchStatus = ImageBatchStatus.SUBMITTED
    }

    fun close(status: ImageBatchStatus) {
        require(status == ImageBatchStatus.COLLECTED || status == ImageBatchStatus.FAILED) {
            "마감 상태는 COLLECTED/FAILED 만 가능합니다: $status"
        }
        this.batchStatus = status
        this.collectedAt = LocalDateTime.now()
    }
}
