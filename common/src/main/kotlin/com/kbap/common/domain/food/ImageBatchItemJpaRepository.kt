package com.kbap.common.domain.food

import com.kbap.common.domain.food.model.ImageBatchItem
import com.kbap.common.domain.food.model.ImageBatchItemStatus
import org.springframework.data.jpa.repository.JpaRepository

interface ImageBatchItemJpaRepository : JpaRepository<ImageBatchItem, Long> {
    fun findByBatchIdAndItemStatus(batchId: Long, itemStatus: ImageBatchItemStatus): List<ImageBatchItem>
}
