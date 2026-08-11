package com.kbap.api.review

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.review.model.Review
import com.kbap.common.util.ImageUrls
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "리뷰 대상 음식 요약 — 목록 조회에서만 채워지며, 음식이 삭제됐으면 null")
data class ReviewFoodResponse(
    @field:Schema(description = "음식 id", example = "1")
    val foodId: Long,

    @field:Schema(description = "요청 언어(lang)로 해석한 음식 표시 이름", example = "Kimbap")
    val name: String,

    @field:Schema(description = "음식 대표 이미지 URL(없으면 null)")
    val imageUrl: String?,
) {
    companion object {
        fun from(food: Food, lang: LanguageCode, imagePublicBaseUrl: String): ReviewFoodResponse =
            ReviewFoodResponse(
                foodId = food.id,
                name = food.displayName(lang),
                imageUrl = ImageUrls.resolve(imagePublicBaseUrl, food.imageRef),
            )
    }
}

@Schema(description = "리뷰 단건 응답")
data class ReviewResponse(
    @field:Schema(description = "리뷰 id", example = "42")
    val reviewId: Long,

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

    @field:Schema(description = "리뷰 대상 음식 요약. 목록 조회에서만 채워지고 작성·수정 응답과 삭제된 음식이면 null", nullable = true)
    val food: ReviewFoodResponse? = null,
) {
    companion object {
        fun from(
            review: Review,
            imagePublicBaseUrl: String,
            author: ReviewAuthorResponse?,
            likeCount: Long,
            likedByMe: Boolean,
            food: ReviewFoodResponse? = null,
        ): ReviewResponse =
            ReviewResponse(
                reviewId = review.id,
                rating = review.rating,
                content = review.content,
                imageUrls = review.imageRefs.orEmpty().mapNotNull { ImageUrls.resolve(imagePublicBaseUrl, it) },
                createdAt = review.createdAt,
                author = author,
                likeCount = likeCount,
                likedByMe = likedByMe,
                food = food,
            )
    }
}
