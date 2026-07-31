package com.kbap.api.review

import com.kbap.common.domain.review.model.Review
import com.kbap.common.domain.review.model.ReviewPlace
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal

@Schema(description = "리뷰 작성 요청")
data class ReviewCreateRequest(
    @field:NotNull(message = "foodId 는 필수입니다")
    @field:Schema(description = "리뷰 대상 음식 id", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    val foodId: Long?,

    @field:NotNull(message = "rating 은 필수입니다")
    @field:Min(1, message = "rating 은 1 이상이어야 합니다")
    @field:Max(5, message = "rating 은 5 이하여야 합니다")
    @field:Schema(description = "별점(1~5 정수)", example = "4", requiredMode = Schema.RequiredMode.REQUIRED)
    val rating: Int?,

    @field:Size(max = Review.MAX_CONTENT_LENGTH, message = "본문은 최대 1000자입니다")
    @field:Schema(description = "리뷰 본문(옵션, 최대 1000자)", example = "정말 맛있어요")
    val content: String? = null,

    @field:Size(max = Review.MAX_IMAGE_COUNT, message = "사진은 최대 3장입니다")
    @field:Schema(description = "업로드 완료된 리뷰 사진 경로(옵션, 최대 3장)")
    val imagePaths: List<String>? = null,

    @field:Valid
    @field:Schema(description = "식당 검색(GET /api/v1/places)에서 고른 식당(옵션). 고르지 않았으면 생략한다")
    val place: ReviewPlaceRequest? = null,
)

@Schema(description = "리뷰 수정 요청 — content·imagePaths 는 보낸 값으로 전량 교체된다")
data class ReviewUpdateRequest(
    @field:NotNull(message = "rating 은 필수입니다")
    @field:Min(1, message = "rating 은 1 이상이어야 합니다")
    @field:Max(5, message = "rating 은 5 이하여야 합니다")
    @field:Schema(description = "별점(1~5 정수)", example = "5", requiredMode = Schema.RequiredMode.REQUIRED)
    val rating: Int?,

    @field:Size(max = Review.MAX_CONTENT_LENGTH, message = "본문은 최대 1000자입니다")
    @field:Schema(description = "리뷰 본문(생략 시 본문 제거)", example = "다시 먹어보니 더 맛있네요")
    val content: String? = null,

    @field:Size(max = Review.MAX_IMAGE_COUNT, message = "사진은 최대 3장입니다")
    @field:Schema(description = "업로드 완료된 리뷰 사진 경로(생략 시 사진 제거)")
    val imagePaths: List<String>? = null,

    @field:Valid
    @field:Schema(description = "식당 정보(생략 시 기존 식당 정보 제거)")
    val place: ReviewPlaceRequest? = null,
)

@Schema(description = "리뷰에 함께 저장할 식당 정보 — 전 항목 선택값(검색 결과의 결측을 그대로 허용)")
data class ReviewPlaceRequest(
    @field:Size(max = ReviewPlace.MAX_NAME_LENGTH, message = "식당명은 최대 100자입니다")
    @field:Schema(description = "식당명", example = "한밥집 강남점")
    val name: String? = null,

    @field:Size(max = ReviewPlace.MAX_ADDRESS_LENGTH, message = "주소는 최대 200자입니다")
    @field:Schema(description = "주소", example = "서울 강남구 테헤란로 123")
    val address: String? = null,

    @field:Size(max = ReviewPlace.MAX_KAKAO_PLACE_ID_LENGTH, message = "카카오 장소 id 는 최대 30자입니다")
    @field:Schema(description = "카카오 장소 id", example = "27290047")
    val kakaoPlaceId: String? = null,

    @field:DecimalMin(value = "-90", message = "위도는 -90 이상이어야 합니다")
    @field:DecimalMax(value = "90", message = "위도는 90 이하여야 합니다")
    @field:Schema(description = "위도", example = "37.4979502")
    val latitude: BigDecimal? = null,

    @field:DecimalMin(value = "-180", message = "경도는 -180 이상이어야 합니다")
    @field:DecimalMax(value = "180", message = "경도는 180 이하여야 합니다")
    @field:Schema(description = "경도", example = "127.0276368")
    val longitude: BigDecimal? = null,
) {
    fun toDomain(): ReviewPlace = ReviewPlace(
        name = name,
        address = address,
        kakaoPlaceId = kakaoPlaceId,
        latitude = latitude,
        longitude = longitude,
    )
}
