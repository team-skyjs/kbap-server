package com.kbap.api.admin

import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodContentStatus
import java.time.LocalDateTime

data class AdminFoodListResponse(
    val items: List<AdminFoodListItemResponse>,
    val page: Int,
    val totalPages: Int,
    val totalCount: Long,
    val hasPrev: Boolean,
    val hasNext: Boolean,
) {
    companion object {
        fun from(view: AdminFoodListPageView): AdminFoodListResponse =
            AdminFoodListResponse(
                items = view.items.map(AdminFoodListItemResponse::from),
                page = view.page,
                totalPages = view.totalPages,
                totalCount = view.totalCount,
                hasPrev = view.hasPrev,
                hasNext = view.hasNext,
            )
    }
}

data class AdminFoodListItemResponse(
    val id: Long,
    val koreanName: String,
    val englishName: String?,
    val contentStatus: FoodContentStatus,
    val contentFailureKind: FoodContentFailureKind?,
    val spiciness: Int,
    val imageUrl: String?,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(view: AdminFoodSummaryView): AdminFoodListItemResponse =
            AdminFoodListItemResponse(
                id = view.id,
                koreanName = view.koreanName,
                englishName = view.englishName,
                contentStatus = view.contentStatus,
                contentFailureKind = view.contentFailureKind,
                spiciness = view.spiciness,
                imageUrl = view.imageUrl,
                updatedAt = view.updatedAt,
            )
    }
}
