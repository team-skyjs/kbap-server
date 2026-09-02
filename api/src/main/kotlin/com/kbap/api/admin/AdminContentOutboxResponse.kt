package com.kbap.api.admin

import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import java.time.LocalDateTime

data class AdminContentOutboxPageResponse(
    val items: List<AdminContentOutboxItemResponse>,
    val page: Int,
    val totalPages: Int,
    val totalCount: Long,
    val hasPrev: Boolean,
    val hasNext: Boolean,
)

data class AdminContentOutboxItemResponse(
    val id: Long,
    val foodId: Long,
    val displayName: String,
    val outboxStatus: FoodContentOutboxStatus,
    val attempts: Int,
    val createdAt: LocalDateTime,
    val sentAt: LocalDateTime?,
) {
    companion object {
        fun from(outbox: FoodContentOutbox): AdminContentOutboxItemResponse =
            AdminContentOutboxItemResponse(
                id = outbox.id,
                foodId = outbox.foodId,
                displayName = outbox.displayName,
                outboxStatus = outbox.outboxStatus,
                attempts = outbox.attempts,
                createdAt = outbox.createdAt,
                sentAt = outbox.sentAt,
            )
    }
}
