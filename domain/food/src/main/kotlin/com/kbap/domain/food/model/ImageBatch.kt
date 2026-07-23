package com.kbap.domain.food.model

import com.kbap.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

// 이미지 배치 메타(KB-226) — 배치 상태의 원천은 OpenAI 가 아니라 이 테이블이다.
@Entity
@Table(
    name = "image_batch",
    uniqueConstraints = [UniqueConstraint(name = "uq_image_batch_openai_batch_id", columnNames = ["openai_batch_id"])],
    indexes = [Index(name = "idx_image_batch_status", columnList = "batch_status")],
)
class ImageBatch(
    @Column(name = "openai_batch_id", nullable = false, length = 100)
    var openaiBatchId: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "batch_status", nullable = false, columnDefinition = "ENUM('SUBMITTED','COLLECTED','FAILED')")
    var batchStatus: ImageBatchStatus = ImageBatchStatus.SUBMITTED,

    @Column(name = "prompt_version", nullable = false, length = 20)
    var promptVersion: String = "",

    @Column(name = "model", nullable = false, length = 50)
    var model: String = "",

    @Column(name = "submitted_at", nullable = false)
    var submittedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "collected_at")
    var collectedAt: LocalDateTime? = null,
) : BaseEntity() {
    fun close(status: ImageBatchStatus) {
        require(status != ImageBatchStatus.SUBMITTED) { "SUBMITTED 로는 마감할 수 없습니다" }
        this.batchStatus = status
        this.collectedAt = LocalDateTime.now()
    }
}
