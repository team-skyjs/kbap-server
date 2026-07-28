package com.kbap.common.domain.food

import com.kbap.common.domain.food.model.ImageBatch
import com.kbap.common.domain.food.model.ImageBatchStatus
import org.springframework.data.jpa.repository.JpaRepository

interface ImageBatchJpaRepository : JpaRepository<ImageBatch, Long> {
    fun findByBatchStatus(batchStatus: ImageBatchStatus): List<ImageBatch>
}
