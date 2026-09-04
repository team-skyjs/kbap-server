package com.kbap.api.admin

import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.util.LikeWildcards
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class AdminFoodOutboxQueryService(
    private val outboxRepository: FoodContentOutboxJpaRepository,
) {
    @Transactional(readOnly = true)
    fun getContentOutboxPage(
        page: Int,
        status: FoodContentOutboxStatus?,
        query: String? = null,
    ): AdminContentOutboxPageResponse {
        val keyword = query?.trim()?.takeIf { it.isNotEmpty() }
        val pageable = PageRequest.of(page - 1, CONTENT_OUTBOX_PAGE_SIZE, Sort.by(Sort.Direction.DESC, "id"))
        val result = when {
            keyword == null && status == null -> outboxRepository.findAll(pageable)
            keyword == null -> outboxRepository.findByOutboxStatus(status!!, pageable)
            status == null -> outboxRepository.searchByKeyword(LikeWildcards.escape(keyword), keyword.toLongOrNull(), pageable)
            else -> outboxRepository.searchByKeywordAndStatus(LikeWildcards.escape(keyword), keyword.toLongOrNull(), status, pageable)
        }
        return AdminContentOutboxPageResponse(
            items = result.content.map(AdminContentOutboxItemResponse::from),
            page = page,
            totalPages = result.totalPages,
            totalCount = result.totalElements,
            hasPrev = page > 1,
            hasNext = page < result.totalPages,
        )
    }

    @Transactional(readOnly = true)
    fun getOutboxDashboard(): AdminFoodOutboxDashboardView =
        AdminFoodOutboxDashboardView(
            pending = outboxRepository.countByOutboxStatus(FoodContentOutboxStatus.PENDING),
            sent = outboxRepository.countByOutboxStatus(FoodContentOutboxStatus.SENT),
            complete = outboxRepository.countByOutboxStatus(FoodContentOutboxStatus.COMPLETE),
            recent = outboxRepository.findTop20ByOrderByIdDesc().map(AdminFoodOutboxRowView::from),
        )

    companion object {
        const val CONTENT_OUTBOX_PAGE_SIZE = 50
    }
}

data class AdminFoodOutboxDashboardView(
    val pending: Long,
    val sent: Long,
    val complete: Long,
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
