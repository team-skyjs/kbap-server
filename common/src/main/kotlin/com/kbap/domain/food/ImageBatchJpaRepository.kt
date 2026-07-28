package com.kbap.domain.food

import com.kbap.domain.food.model.ImageBatch
import com.kbap.domain.food.model.ImageBatchStatus
import org.springframework.data.jpa.repository.JpaRepository

interface ImageBatchJpaRepository : JpaRepository<ImageBatch, Long> {
    fun findByBatchStatus(batchStatus: ImageBatchStatus): List<ImageBatch>
}
