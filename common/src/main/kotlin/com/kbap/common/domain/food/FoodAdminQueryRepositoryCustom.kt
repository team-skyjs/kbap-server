package com.kbap.common.domain.food

import com.kbap.common.domain.food.dto.AdminFoodRow
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodContentStatus

enum class AdminFoodSort(val column: String) {
    ID("f.id"),
    UPDATED_AT("f.updated_at"),
    DISPLAY_NAME("f.display_name"),
}

data class AdminFoodFilter(
    val q: String? = null,
    val ingredient: String? = null,
    val translation: String? = null,
    val status: FoodContentStatus? = null,
    val failureKind: FoodContentFailureKind? = null,
    val includeDeleted: Boolean = false,
    val sort: AdminFoodSort = AdminFoodSort.ID,
    val descending: Boolean = true,
)

data class AdminFoodRows(
    val rows: List<AdminFoodRow>,
    val totalCount: Long,
)

interface FoodAdminQueryRepositoryCustom {
    fun findAdminPage(filter: AdminFoodFilter, page: Int, size: Int): AdminFoodRows
}
