package com.kbap.common.domain.food.dto

import com.kbap.common.domain.food.model.ImageBatchItemStatus

data class ImageBatchItemCount(
    val batchId: Long,
    val itemStatus: ImageBatchItemStatus,
    val count: Long,
)
