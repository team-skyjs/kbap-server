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

    @field:Schema(description = "작성 시각")
    val createdAt: LocalDateTime,

    @field:Schema(description = "작성자 프로필(닉네임·랭킹·현재 국적). 탈퇴한 회원이면 null.", nullable = true)
    val author: ReviewAuthorResponse?,

    @field:Schema(description = "좋아요 수", example = "3")
    val likeCount: Long,

    @field:Schema(description = "조회 회원이 좋아요를 눌렀는지", example = "true")
    val likedByMe: Boolean,
) {
    companion object {
        fun from(
            review: Review,
            imagePublicBaseUrl: String,
            author: ReviewAuthorResponse?,
            likeCount: Long,
            likedByMe: Boolean,
        ): ReviewResponse =
            ReviewResponse(
                reviewId = review.id,
                foodId = review.foodId,
                memberId = review.memberId,
                rating = review.rating,
                content = review.content,
                imageUrls = review.imageRefs.orEmpty().mapNotNull { ImageUrls.resolve(imagePublicBaseUrl, it) },
                createdAt = review.createdAt,
                author = author,
                likeCount = likeCount,
                likedByMe = likedByMe,
            )
    }
}
