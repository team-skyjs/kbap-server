package com.kbap.api.review

import com.kbap.common.domain.review.model.Review
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

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
)
