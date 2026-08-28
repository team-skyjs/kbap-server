package com.kbap.common.domain.food.dto

import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodContentStatus
import java.time.LocalDateTime

data class AdminFoodRow(
    val id: Long,
    val koreanName: String,
    val displayName: String,
    val contentStatus: FoodContentStatus,
    val contentFailureKind: FoodContentFailureKind?,
    val spiciness: Int,
    val imageRef: String?,
    val contentReviewAttempts: Int,
    val deleted: Boolean,
    val updatedAt: LocalDateTime,
)
