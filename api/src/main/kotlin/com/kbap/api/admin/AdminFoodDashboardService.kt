package com.kbap.api.admin

import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.FoodContentStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.math.round

@Service
class AdminFoodDashboardService(
    private val foodRepository: FoodJpaRepository,
) {
    @Transactional(readOnly = true)
    fun getDashboard(): AdminFoodDashboardView {
        val counts = foodRepository.countGroupByContentStatus().associate { it.status to it.count }
        fun countOf(status: FoodContentStatus): Long = counts[status] ?: 0L

        val total = counts.values.sum()
        val ready = countOf(FoodContentStatus.READY)
        return AdminFoodDashboardView(
            total = total,
            incomplete = countOf(FoodContentStatus.INCOMPLETE),
            pendingImage = countOf(FoodContentStatus.PENDING_IMAGE),
            pendingReview = countOf(FoodContentStatus.PENDING_REVIEW),
            ready = ready,
            readyRatio = if (total == 0L) 0.0 else round(ready * 1000.0 / total) / 10.0,
        )
    }
}

data class AdminFoodDashboardView(
    val total: Long,
    val incomplete: Long,
    val pendingImage: Long,
    val pendingReview: Long,
    val ready: Long,
    val readyRatio: Double,
)
