package com.kbap.api.admin

import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodVectorOutbox
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
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
            unenqueued = foodRepository.countReadyWithoutVectorUpsertOutbox(),
            failures = failures.map { AdminVectorOutboxRowView.from(it, displayNames[it.foodId]) },
        )
    }

    @Transactional(readOnly = true)
    fun getVectorOutboxPage(
        page: Int,
        status: FoodVectorOutboxStatus?,
        query: String? = null,
    ): AdminVectorOutboxPageResponse {
        val keyword = query?.trim()?.takeIf { it.isNotEmpty() }
        val pageable = PageRequest.of(page - 1, VECTOR_OUTBOX_PAGE_SIZE, Sort.by(Sort.Direction.DESC, "id"))
        val result = when {
            keyword == null && status == null -> vectorOutboxRepository.findAll(pageable)
            keyword == null -> vectorOutboxRepository.findByOutboxStatus(status!!, pageable)
            else -> {
                val foodId = keyword.toLongOrNull()
                when (status) {
                    null -> vectorOutboxRepository.searchByFoodKeyword(keyword, foodId, pageable)
                    else -> vectorOutboxRepository.searchByFoodKeywordAndStatus(keyword, foodId, status, pageable)
                }
            }
        }
        val displayNames = foodRepository.findAllById(result.content.map { it.foodId }.distinct())
            .associate { it.id to it.displayName }
        return AdminVectorOutboxPageResponse(
            items = result.content.map { AdminVectorOutboxItemResponse.from(it, displayNames[it.foodId]) },
            page = page,
            totalPages = result.totalPages,
            totalCount = result.totalElements,
            hasPrev = page > 1,
            hasNext = page < result.totalPages,
        )
    }

    @Transactional
    fun enqueueReadyFoodsForVectorSync(): Int {
        val targetIds = foodRepository.findReadyIdsWithoutVectorUpsertOutbox(PageRequest.of(0, ENQUEUE_MAX))
        vectorOutboxRepository.saveAll(targetIds.map { FoodVectorOutbox.upsert(it) })
        return targetIds.size
    }

    @Transactional
    fun retryVectorOutbox(outboxId: Long): AdminVectorOutboxRetryResult {
        val outbox = vectorOutboxRepository.findById(outboxId).orElse(null)
            ?: return AdminVectorOutboxRetryResult.NOT_FOUND
        return when (outbox.outboxStatus) {
            FoodVectorOutboxStatus.FAILED -> {
                outbox.retry()
                AdminVectorOutboxRetryResult.RETRIED
            }
            FoodVectorOutboxStatus.PENDING -> AdminVectorOutboxRetryResult.ALREADY_PENDING
            FoodVectorOutboxStatus.COMPLETE -> AdminVectorOutboxRetryResult.ALREADY_COMPLETE
        }
    }

    companion object {
        const val ENQUEUE_MAX = 500

        const val VECTOR_OUTBOX_PAGE_SIZE = 50
    }
}

enum class AdminVectorOutboxRetryResult {
    RETRIED,
    ALREADY_PENDING,
    ALREADY_COMPLETE,
    NOT_FOUND,
}

data class AdminVectorOutboxDashboardView(
    val pending: Long,
    val complete: Long,
    val failed: Long,
    val unenqueued: Long,
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
