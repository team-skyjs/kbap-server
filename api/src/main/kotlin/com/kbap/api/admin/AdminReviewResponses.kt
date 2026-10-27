package com.kbap.api.admin

import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.review.model.Review
import com.kbap.common.util.ImageUrls
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class AdminReviewPageResponse(
    val items: List<AdminReviewItemResponse>,
    val page: Int,
    val totalPages: Int,
    val totalCount: Long,
    val hasPrev: Boolean,
    val hasNext: Boolean,
)

data class AdminReviewItemResponse(
    val id: Long,
    val memberId: Long,
    val memberNickname: String?,
    @field:Schema(description = "작성자가 리뷰 봇 계정인지", example = "false")
    val isBot: Boolean,
    @field:Schema(description = "FOOD | PROCESSED_FOOD", example = "FOOD")
    val targetType: String,
    val foodId: Long?,
    val processedFoodId: Long?,
    @field:Schema(description = "음식 표시명 또는 가공식품 제품명(대상이 사라졌으면 null)")
    val targetName: String?,
    val rating: Int,
    val servingSpeedRating: Int,
    val staffKindnessRating: Int,
    val content: String?,
    val imageUrls: List<String>,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(review: Review, author: Member?, foodNames: Map<Long, String>, productNames: Map<Long, String>, imagePublicBaseUrl: String): AdminReviewItemResponse =
            AdminReviewItemResponse(
                id = review.id,
                memberId = review.memberId,
                memberNickname = author?.nickname,
                isBot = author?.isBot == true,
                targetType = review.targetType.name,
                foodId = review.foodId,
                processedFoodId = review.processedFoodId,
                targetName = review.foodId?.let(foodNames::get) ?: review.processedFoodId?.let(productNames::get),
                rating = review.rating,
                servingSpeedRating = review.servingSpeedRating,
                staffKindnessRating = review.staffKindnessRating,
                content = review.content,
                imageUrls = review.imageRefs.orEmpty().mapNotNull { ImageUrls.resolve(imagePublicBaseUrl, it) },
                createdAt = review.createdAt,
            )
    }
}
