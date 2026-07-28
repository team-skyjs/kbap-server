package com.kbap.common.domain.food.model

import com.kbap.common.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table

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
