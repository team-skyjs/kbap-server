package com.kbap.api.admin

import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class AdminFoodOutboxQueryService(
    private val outboxRepository: FoodContentOutboxJpaRepository,
) {
    @Transactional(readOnly = true)
    fun getOutboxDashboard(): AdminFoodOutboxDashboardView =
        AdminFoodOutboxDashboardView(
            pending = outboxRepository.countByOutboxStatus(FoodContentOutboxStatus.PENDING),
            sent = outboxRepository.countByOutboxStatus(FoodContentOutboxStatus.SENT),
            recent = outboxRepository.findTop20ByOrderByIdDesc().map(AdminFoodOutboxRowView::from),
        )
}

data class AdminFoodOutboxDashboardView(
    val pending: Long,
    val sent: Long,
    val recent: List<AdminFoodOutboxRowView>,
)

data class AdminFoodOutboxRowView(
    val foodId: Long,
    val displayName: String,
    val outboxStatus: FoodContentOutboxStatus,
    val attempts: Int,
    val createdAt: LocalDateTime,
    val sentAt: LocalDateTime?,
) {
    companion object {
        fun from(outbox: FoodContentOutbox): AdminFoodOutboxRowView =
            AdminFoodOutboxRowView(
                foodId = outbox.foodId,
                displayName = outbox.displayName,
                outboxStatus = outbox.outboxStatus,
                attempts = outbox.attempts,
                createdAt = outbox.createdAt,
                sentAt = outbox.sentAt,
            )
    }
}
