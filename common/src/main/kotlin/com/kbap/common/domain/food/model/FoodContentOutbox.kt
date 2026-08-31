package com.kbap.common.domain.food.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "food_content_outbox")
class FoodContentOutbox(
    @Column(name = "food_id", nullable = false)
    var foodId: Long = 0,

    @Column(name = "display_name", nullable = false, length = 255)
    var displayName: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "outbox_status", nullable = false, columnDefinition = "ENUM('PENDING','SENT','COMPLETE')")
    var outboxStatus: FoodContentOutboxStatus = FoodContentOutboxStatus.PENDING,

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0,

    @Column(name = "sent_at")
    var sentAt: LocalDateTime? = null,
) : BaseEntity() {
    fun markSent() {
        attempts++
        if (outboxStatus == FoodContentOutboxStatus.PENDING) {
            outboxStatus = FoodContentOutboxStatus.SENT
        }
        if (sentAt == null) {
            sentAt = LocalDateTime.now()
        }
    }

    fun markFailed() {
        attempts++
    }

    companion object {
        fun pending(foodId: Long, displayName: String): FoodContentOutbox {
            require(displayName.isNotBlank()) { "foodContentOutbox.displayName 은 blank 일 수 없습니다" }
            return FoodContentOutbox(foodId = foodId, displayName = displayName)
        }
    }
}
