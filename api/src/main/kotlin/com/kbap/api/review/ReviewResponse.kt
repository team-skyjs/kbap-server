package com.kbap.api.review

import com.kbap.common.domain.review.model.Review
import com.kbap.common.domain.review.model.ReviewPlace
import com.kbap.common.util.ImageUrls
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
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

    @field:Schema(description = "작성 시 고른 식당 정보. 고르지 않았으면 null.", nullable = true)
    val place: ReviewPlaceResponse?,
) {
    companion object {
        fun from(review: Review, imagePublicBaseUrl: String, author: ReviewAuthorResponse?): ReviewResponse =
            ReviewResponse(
                reviewId = review.id,
                foodId = review.foodId,
                memberId = review.memberId,
                rating = review.rating,
                content = review.content,
                imageUrls = review.imageRefs.orEmpty().mapNotNull { ImageUrls.resolve(imagePublicBaseUrl, it) },
                createdAt = review.createdAt,
                author = author,
                place = review.place?.let(ReviewPlaceResponse::from),
            )
    }
}

@Schema(description = "리뷰에 저장된 식당 정보 — 각 항목은 저장 당시 결측이면 null")
data class ReviewPlaceResponse(
    @field:Schema(description = "식당명", example = "한밥집 강남점")
    val name: String?,

    @field:Schema(description = "주소", example = "서울 강남구 테헤란로 123")
    val address: String?,

    @field:Schema(description = "카카오 장소 id", example = "27290047")
    val kakaoPlaceId: String?,

    @field:Schema(description = "위도", example = "37.4979502")
    val latitude: BigDecimal?,

    @field:Schema(description = "경도", example = "127.0276368")
    val longitude: BigDecimal?,
) {
    companion object {
        fun from(place: ReviewPlace): ReviewPlaceResponse =
            ReviewPlaceResponse(
                name = place.name,
                address = place.address,
                kakaoPlaceId = place.kakaoPlaceId,
                latitude = place.latitude,
                longitude = place.longitude,
            )
    }
}
