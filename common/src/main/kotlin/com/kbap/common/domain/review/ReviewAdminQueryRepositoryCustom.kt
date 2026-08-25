package com.kbap.common.domain.review

import com.kbap.common.domain.review.model.Review

data class AdminReviewFilter(
    val q: String? = null,
    val memberId: Long? = null,
    val foodId: Long? = null,
    val reported: Boolean? = null,
    val hasImage: Boolean? = null,
)

data class AdminReviewRows(
    val rows: List<Review>,
    val totalCount: Long,
)

interface ReviewAdminQueryRepositoryCustom {
    fun findAdminPage(filter: AdminReviewFilter, page: Int, size: Int): AdminReviewRows
}
