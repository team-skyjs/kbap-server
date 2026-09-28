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
    @field:io.swagger.v3.oas.annotations.media.Schema(description = "조건에 맞지만 이번 호출(최대 500)에 담기지 않은 수. 0 이 될 때까지 반복 호출한다", example = "0")
    val remaining: Long,
    @field:io.swagger.v3.oas.annotations.media.Schema(description = "force 모드의 다음 커서 — 이번 페이지 마지막 foodId. 더 없으면 null. 다음 호출에 afterFoodId 로 넘긴다", example = "500", nullable = true)
    val nextAfterFoodId: Long?,
) {
    companion object {
        fun from(result: AdminVectorOutboxEnqueueResult): AdminVectorOutboxEnqueueResponse =
            AdminVectorOutboxEnqueueResponse(enqueued = result.enqueued, remaining = result.remaining, nextAfterFoodId = result.nextAfterFoodId)
    }
}

data class AdminVectorOutboxRetryResponse(
    val retried: Boolean,
    val outboxStatus: FoodVectorOutboxStatus,
)
