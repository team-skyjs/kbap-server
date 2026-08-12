package com.kbap.common.domain.food.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table

@Entity
@Table(name = "food_vector_outbox")
class FoodVectorOutbox(
    @Column(name = "food_id", nullable = false)
    var foodId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, columnDefinition = "ENUM('UPSERT','DELETE')")
    var operation: FoodVectorOutboxOperation = FoodVectorOutboxOperation.UPSERT,

    @Enumerated(EnumType.STRING)
    @Column(name = "outbox_status", nullable = false, columnDefinition = "ENUM('PENDING','COMPLETE','FAILED')")
    var outboxStatus: FoodVectorOutboxStatus = FoodVectorOutboxStatus.PENDING,

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0,

    @Column(name = "last_error", length = MAX_LAST_ERROR_LENGTH)
    var lastError: String? = null,
) : BaseEntity() {
    fun complete() {
        outboxStatus = FoodVectorOutboxStatus.COMPLETE
    }

    fun recordFailure(error: String?) {
        attempts++
        lastError = error?.take(MAX_LAST_ERROR_LENGTH)
        if (attempts >= MAX_ATTEMPTS) {
            outboxStatus = FoodVectorOutboxStatus.FAILED
        }
    }

    fun retry() {
        outboxStatus = FoodVectorOutboxStatus.PENDING
        attempts = 0
    }

    companion object {
        const val MAX_ATTEMPTS = 5

        const val MAX_LAST_ERROR_LENGTH = 500

        fun upsert(foodId: Long): FoodVectorOutbox =
            FoodVectorOutbox(foodId = foodId, operation = FoodVectorOutboxOperation.UPSERT)

        fun delete(foodId: Long): FoodVectorOutbox =
            FoodVectorOutbox(foodId = foodId, operation = FoodVectorOutboxOperation.DELETE)
    }
}
