package com.kbap.api.review

import com.kbap.common.domain.review.model.PlaceSource
import com.kbap.common.domain.review.model.Review
import com.kbap.common.domain.review.model.ReviewPlace
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
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

    @field:Min(0, message = "servingSpeed 는 0 이상이어야 합니다")
    @field:Max(5, message = "servingSpeed 는 5 이하여야 합니다")
    @field:Schema(description = "제공 속도 평가(0~5 정수) — 0 은 평가 안 함, 누락 시 0", example = "5")
    val servingSpeed: Int? = null,

    @field:Min(0, message = "staffKindness 는 0 이상이어야 합니다")
    @field:Max(5, message = "staffKindness 는 5 이하여야 합니다")
    @field:Schema(description = "직원 친절도 평가(0~5 정수) — 0 은 평가 안 함, 누락 시 0", example = "4")
    val staffKindness: Int? = null,

    @field:Size(max = Review.MAX_CONTENT_LENGTH, message = "본문은 최대 1000자입니다")
    @field:Schema(description = "리뷰 본문(옵션, 최대 1000자)", example = "정말 맛있어요")
    val content: String? = null,

    @field:Size(max = Review.MAX_IMAGE_COUNT, message = "사진은 최대 3장입니다")
    @field:Schema(description = "업로드 완료된 리뷰 사진 경로(옵션, 최대 3장)")
    val imagePaths: List<String>? = null,

    @field:Valid
    @field:Schema(description = "위치 정보(옵션) — 검색에서 고른 식당 또는 동의받은 작성자 좌표. 없으면 생략한다")
    val place: ReviewPlaceRequest? = null,
)

@Schema(description = "리뷰 수정 요청 — content·imagePaths 는 보낸 값으로 전량 교체된다")
data class ReviewUpdateRequest(
    @field:NotNull(message = "rating 은 필수입니다")
    @field:Min(1, message = "rating 은 1 이상이어야 합니다")
    @field:Max(5, message = "rating 은 5 이하여야 합니다")
    @field:Schema(description = "별점(1~5 정수)", example = "5", requiredMode = Schema.RequiredMode.REQUIRED)
    val rating: Int?,

    @field:Min(0, message = "servingSpeed 는 0 이상이어야 합니다")
    @field:Max(5, message = "servingSpeed 는 5 이하여야 합니다")
    @field:Schema(description = "제공 속도 평가(0~5 정수) — 0 은 평가 안 함, 생략 시 0 으로 교체", example = "5")
    val servingSpeed: Int? = null,

    @field:Min(0, message = "staffKindness 는 0 이상이어야 합니다")
    @field:Max(5, message = "staffKindness 는 5 이하여야 합니다")
    @field:Schema(description = "직원 친절도 평가(0~5 정수) — 0 은 평가 안 함, 생략 시 0 으로 교체", example = "4")
    val staffKindness: Int? = null,

    @field:Size(max = Review.MAX_CONTENT_LENGTH, message = "본문은 최대 1000자입니다")
    @field:Schema(description = "리뷰 본문(생략 시 본문 제거)", example = "다시 먹어보니 더 맛있네요")
    val content: String? = null,

    @field:Size(max = Review.MAX_IMAGE_COUNT, message = "사진은 최대 3장입니다")
    @field:Schema(description = "업로드 완료된 리뷰 사진 경로(생략 시 사진 제거)")
    val imagePaths: List<String>? = null,

    @field:Valid
    @field:Schema(description = "위치 정보(생략 시 기존 위치 정보 제거)")
    val place: ReviewPlaceRequest? = null,
)

@Schema(
    description = "리뷰에 함께 저장할 위치 정보. 세 형태 — ① 식당 검색(GET /api/places)에서 고른 항목 그대로(KAKAO_PLACE), " +
        "② GPS 미동의 시 사용자가 입력한 식당명 텍스트만(MANUAL), ③ 식당 미선택 + GPS 동의 시 작성자 좌표만(AUTHOR_LOCATION). " +
        "출처는 서버가 유도한다(name+좌표 양쪽 → KAKAO_PLACE, name 만 → MANUAL, 좌표만 → AUTHOR_LOCATION)",
)
data class ReviewPlaceRequest(
    @field:Size(max = ReviewPlace.MAX_NAME_LENGTH, message = "식당명은 최대 100자입니다")
    @field:Schema(description = "식당명", example = "한밥집 강남점")
    val name: String? = null,

    @field:Size(max = ReviewPlace.MAX_ADDRESS_LENGTH, message = "주소는 최대 200자입니다")
    @field:Schema(description = "주소", example = "서울 강남구 테헤란로 123")
    val address: String? = null,

    @field:DecimalMin(value = "-90", message = "위도는 -90 이상이어야 합니다")
    @field:DecimalMax(value = "90", message = "위도는 90 이하여야 합니다")
    @field:Schema(description = "위도", example = "37.4979502")
    val latitude: BigDecimal? = null,

    @field:DecimalMin(value = "-180", message = "경도는 -180 이상이어야 합니다")
    @field:DecimalMax(value = "180", message = "경도는 180 이하여야 합니다")
    @field:Schema(description = "경도", example = "127.0276368")
    val longitude: BigDecimal? = null,
) {
    @get:AssertTrue(message = "latitude·longitude 는 함께 보내거나 함께 생략해야 합니다")
    @get:Schema(hidden = true)
    val coordinatesComplete: Boolean
        get() = (latitude != null) == (longitude != null)

    fun toDomain(): ReviewPlace? = when {
        name != null && latitude != null && longitude != null -> ReviewPlace(
            source = PlaceSource.KAKAO_PLACE,
            name = name,
            address = address,
            latitude = latitude,
            longitude = longitude,
        )
        name != null -> ReviewPlace(
            source = PlaceSource.MANUAL,
            name = name,
            address = address,
        )
        latitude != null && longitude != null -> ReviewPlace(
            source = PlaceSource.AUTHOR_LOCATION,
            latitude = latitude,
            longitude = longitude,
        )
        else -> null
    }
}
