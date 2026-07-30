package com.kbap.api.review

import com.kbap.common.domain.review.model.Review
import com.kbap.common.util.ImageUrls
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "리뷰 단건 응답")
data class ReviewResponse(
    @field:Schema(description = "리뷰 id", example = "42")
    val reviewId: Long,

    @field:Schema(description = "리뷰 대상 음식 id", example = "1")
    val foodId: Long,

    @field:Schema(description = "작성자 회원 id", example = "7")
    val memberId: Long,

    @field:Schema(description = "별점(1~5)", example = "4")
    val rating: Int,

    @field:Schema(description = "리뷰 본문(없으면 null)", example = "정말 맛있어요")
    val content: String?,

    @field:Schema(description = "리뷰 사진 URL 목록(없으면 빈 배열)")
    val imageUrls: List<String>,

    @field:Schema(description = "작성 시점 작성자 국적 스냅샷(국적 미보유면 null)", example = "VN")
    val authorCountryCode: String?,

    @field:Schema(description = "작성 시각")
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(review: Review, imagePublicBaseUrl: String): ReviewResponse =
            ReviewResponse(
                reviewId = review.id,
                foodId = review.foodId,
                memberId = review.memberId,
                rating = review.rating,
                content = review.content,
                imageUrls = review.imageRefs.orEmpty().mapNotNull { ImageUrls.resolve(imagePublicBaseUrl, it) },
                authorCountryCode = review.authorCountryCode,
                createdAt = review.createdAt,
            )
    }
}
