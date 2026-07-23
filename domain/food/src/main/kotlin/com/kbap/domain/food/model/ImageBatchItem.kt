package com.kbap.domain.food.model

import com.kbap.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table

// 배치 내 음식 1건(KB-226). 참조는 id 값만 — FK 는 Flyway 가 강제한다(헌법 IV).
// food_id 가 곧 OpenAI custom_id 라 결과 매칭이 DB 조인으로 끝난다.
@Entity
@Table(
    name = "image_batch_item",
    indexes = [
        Index(name = "idx_image_batch_item_batch", columnList = "batch_id"),
        Index(name = "idx_image_batch_item_food_status", columnList = "food_id, item_status"),
    ],
)
class ImageBatchItem(
    @Column(name = "batch_id", nullable = false)
    var batchId: Long = 0,

    @Column(name = "food_id", nullable = false)
    var foodId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "item_status", nullable = false, columnDefinition = "ENUM('PENDING','DONE','FAILED')")
    var itemStatus: ImageBatchItemStatus = ImageBatchItemStatus.PENDING,

    @Column(name = "file_name", length = 500)
    var fileName: String? = null,

    @Column(name = "error_msg", length = 1000)
    var errorMsg: String? = null,
) : BaseEntity() {
    fun done(fileName: String) {
        this.itemStatus = ImageBatchItemStatus.DONE
        this.fileName = fileName
    }

    fun fail(errorMsg: String) {
        this.itemStatus = ImageBatchItemStatus.FAILED
        this.errorMsg = errorMsg.take(1000)
    }
}
