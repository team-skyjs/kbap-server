package com.kbap.api.admin

import java.time.LocalDateTime

data class AdminReviewResponse(
    val id: Long,
    val memberId: Long,
    val memberNickname: String?,
    val foodId: Long,
    val foodDisplayName: String?,
    val rating: Int,
    val servingSpeedRating: Int,
    val staffKindnessRating: Int,
    val content: String?,
    val imageUrls: List<String>,
    val placeName: String?,
    val authorCountryCode: String?,
    val likeCount: Long,
    val reportCount: Long,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

data class AdminReviewPageResponse(
    val items: List<AdminReviewResponse>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
)

data class AdminReviewDeleteResponse(
    val id: Long,
    val memberId: Long,
    val rankingAdjusted: Boolean,
)
