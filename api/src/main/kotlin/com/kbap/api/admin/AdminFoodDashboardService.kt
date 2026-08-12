package com.kbap.api.admin

import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodVectorOutbox
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import kotlin.math.round

@Service
class AdminFoodDashboardService(
    private val foodRepository: FoodJpaRepository,
    private val vectorOutboxRepository: FoodVectorOutboxJpaRepository,
) {
    @Transactional(readOnly = true)
    fun getDashboard(): AdminFoodDashboardView {
        val counts = foodRepository.countGroupByContentStatus().associate { it.status to it.count }
        fun countOf(status: FoodContentStatus): Long = counts[status] ?: 0L

        val total = counts.values.sum()
        val ready = countOf(FoodContentStatus.READY)
        return AdminFoodDashboardView(
            total = total,
            failed = countOf(FoodContentStatus.FAILED),
            pendingImage = countOf(FoodContentStatus.PENDING_IMAGE),
            pendingReview = countOf(FoodContentStatus.PENDING_REVIEW),
            ready = ready,
            readyRatio = if (total == 0L) 0.0 else round(ready * 1000.0 / total) / 10.0,
        )
    }

    @Transactional(readOnly = true)
    fun getVectorOutboxDashboard(): AdminVectorOutboxDashboardView {
        val failures = vectorOutboxRepository.findTop20ByOutboxStatusOrderByIdDesc(FoodVectorOutboxStatus.FAILED)
        val displayNames = foodRepository.findAllById(failures.map { it.foodId })
            .associate { it.id to it.displayName }
        return AdminVectorOutboxDashboardView(
            pending = vectorOutboxRepository.countByOutboxStatus(FoodVectorOutboxStatus.PENDING),
            complete = vectorOutboxRepository.countByOutboxStatus(FoodVectorOutboxStatus.COMPLETE),
            failed = vectorOutboxRepository.countByOutboxStatus(FoodVectorOutboxStatus.FAILED),
            failures = failures.map { AdminVectorOutboxRowView.from(it, displayNames[it.foodId]) },
        )
    }

    @Transactional
    fun retryVectorOutbox(outboxId: Long) {
        val outbox = vectorOutboxRepository.findById(outboxId).orElse(null) ?: return
        if (outbox.outboxStatus == FoodVectorOutboxStatus.FAILED) {
            outbox.retry()
        }
    }
}

data class AdminVectorOutboxDashboardView(
    val pending: Long,
    val complete: Long,
    val failed: Long,
    val failures: List<AdminVectorOutboxRowView>,
)

data class AdminVectorOutboxRowView(
    val outboxId: Long,
    val foodId: Long,
    val displayName: String,
    val operation: FoodVectorOutboxOperation,
    val attempts: Int,
    val lastError: String?,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(outbox: FoodVectorOutbox, displayName: String?): AdminVectorOutboxRowView =
            AdminVectorOutboxRowView(
                outboxId = outbox.id,
                foodId = outbox.foodId,
                displayName = displayName ?: "삭제된 음식(#${outbox.foodId})",
                operation = outbox.operation,
                attempts = outbox.attempts,
                lastError = outbox.lastError,
                updatedAt = outbox.updatedAt,
            )
    }
}

data class AdminFoodDashboardView(
    val total: Long,
    val failed: Long,
    val pendingImage: Long,
    val pendingReview: Long,
    val ready: Long,
    val readyRatio: Double,
)
