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
    fun getOutboxDashboard(stuckHours: Int = DEFAULT_STUCK_HOURS): AdminFoodOutboxDashboardView {
        val stuckBefore = LocalDateTime.now().minusHours(stuckHours.toLong())
        return AdminFoodOutboxDashboardView(
            pending = outboxRepository.countByOutboxStatus(FoodContentOutboxStatus.PENDING),
            sent = outboxRepository.countByOutboxStatus(FoodContentOutboxStatus.SENT),
            complete = outboxRepository.countByOutboxStatus(FoodContentOutboxStatus.COMPLETE),
            canceled = outboxRepository.countByOutboxStatus(FoodContentOutboxStatus.CANCELED),
            stuckCount = outboxRepository.countByOutboxStatusAndSentAtBefore(FoodContentOutboxStatus.SENT, stuckBefore),
            stuckHours = stuckHours,
            stuck = outboxRepository.findTop20ByOutboxStatusAndSentAtBeforeOrderBySentAtAsc(FoodContentOutboxStatus.SENT, stuckBefore)
                .map(AdminFoodOutboxRowView::from),
            recent = outboxRepository.findTop20ByOrderByIdDesc().map(AdminFoodOutboxRowView::from),
        )
    }

    companion object {
        const val DEFAULT_STUCK_HOURS = 3
    }
}

data class AdminFoodOutboxDashboardView(
    val pending: Long,
    val sent: Long,
    val complete: Long,
    val canceled: Long,
    val stuckCount: Long,
    val stuckHours: Int,
    val stuck: List<AdminFoodOutboxRowView>,
    val recent: List<AdminFoodOutboxRowView>,
)

data class AdminFoodOutboxRowView(
    val outboxId: Long,
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
                outboxId = outbox.id,
                foodId = outbox.foodId,
                displayName = outbox.displayName,
                outboxStatus = outbox.outboxStatus,
                attempts = outbox.attempts,
                createdAt = outbox.createdAt,
                sentAt = outbox.sentAt,
            )
    }
}
