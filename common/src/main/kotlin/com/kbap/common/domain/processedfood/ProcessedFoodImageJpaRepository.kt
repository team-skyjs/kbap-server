package com.kbap.common.domain.processedfood

import com.kbap.common.domain.processedfood.model.ProcessedFoodImage
import org.springframework.data.jpa.repository.JpaRepository

interface ProcessedFoodImageJpaRepository : JpaRepository<ProcessedFoodImage, Long> {
    fun findAllByProcessedFoodIdOrderBySortOrderAscIdAsc(processedFoodId: Long): List<ProcessedFoodImage>

    fun findAllByProcessedFoodIdIn(processedFoodIds: Collection<Long>): List<ProcessedFoodImage>
}
