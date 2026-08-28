package com.kbap.common.domain.food

import com.kbap.common.domain.food.dto.ImageBatchItemCount
import com.kbap.common.domain.food.model.ImageBatchItem
import com.kbap.common.domain.food.model.ImageBatchItemStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

interface ImageBatchItemJpaRepository : JpaRepository<ImageBatchItem, Long> {
    fun findByBatchIdAndItemStatus(batchId: Long, itemStatus: ImageBatchItemStatus): List<ImageBatchItem>

    fun findTop10ByFoodIdOrderByIdDesc(foodId: Long): List<ImageBatchItem>

    fun existsByFoodIdAndItemStatus(foodId: Long, itemStatus: ImageBatchItemStatus): Boolean

    fun findByBatchIdOrderByIdAsc(batchId: Long): List<ImageBatchItem>

    @Query(
        """
        select new com.kbap.common.domain.food.dto.ImageBatchItemCount(i.batchId, i.itemStatus, count(i))
        from ImageBatchItem i
        where i.batchId in :batchIds
        group by i.batchId, i.itemStatus
        """,
    )
    fun countGroupByBatchIdAndStatus(batchIds: List<Long>): List<ImageBatchItemCount>

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update ImageBatchItem i set i.fileName = :fileName where i.id = :id and i.fileName is null")
    fun reserveFileName(id: Long, fileName: String): Int
}
