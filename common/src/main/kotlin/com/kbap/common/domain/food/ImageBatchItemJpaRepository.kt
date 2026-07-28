package com.kbap.common.domain.food

import com.kbap.common.domain.food.model.ImageBatchItem
import com.kbap.common.domain.food.model.ImageBatchItemStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

interface ImageBatchItemJpaRepository : JpaRepository<ImageBatchItem, Long> {
    fun findByBatchIdAndItemStatus(batchId: Long, itemStatus: ImageBatchItemStatus): List<ImageBatchItem>

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update ImageBatchItem i set i.fileName = :fileName where i.id = :id and i.fileName is null")
    fun reserveFileName(id: Long, fileName: String): Int
}
