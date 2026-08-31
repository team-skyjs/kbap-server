package com.kbap.api.admin

import com.kbap.common.domain.food.model.FoodVectorOutbox
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import java.time.LocalDateTime

data class AdminVectorOutboxPageResponse(
    val items: List<AdminVectorOutboxItemResponse>,
    val page: Int,
    val totalPages: Int,
    val totalCount: Long,
    val hasPrev: Boolean,
    val hasNext: Boolean,
)

data class AdminVectorOutboxItemResponse(
    val id: Long,
    val foodId: Long,
    val displayName: String?,
    val operation: FoodVectorOutboxOperation,
    val outboxStatus: FoodVectorOutboxStatus,
    val attempts: Int,
    val lastError: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(outbox: FoodVectorOutbox, displayName: String?): AdminVectorOutboxItemResponse =
            AdminVectorOutboxItemResponse(
                id = outbox.id,
                foodId = outbox.foodId,
                displayName = displayName,
                operation = outbox.operation,
                outboxStatus = outbox.outboxStatus,
                attempts = outbox.attempts,
                lastError = outbox.lastError,
                createdAt = outbox.createdAt,
                updatedAt = outbox.updatedAt,
            )
    }
}

data class AdminVectorOutboxEnqueueResponse(
    val enqueued: Int,
)

data class AdminVectorOutboxRetryResponse(
    val retried: Boolean,
    val outboxStatus: FoodVectorOutboxStatus,
)
