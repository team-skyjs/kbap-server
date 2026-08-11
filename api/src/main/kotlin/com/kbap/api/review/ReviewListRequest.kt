package com.kbap.api.review

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

private const val LANG_DESCRIPTION =
    "음식 표시명 언어 코드. 지원: ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. 지원 목록에 없는 값은 en 으로 응답한다."

@Schema(description = "전체 리뷰 피드 조회 파라미터")
data class FeedReviewListRequest(
    @field:NotBlank(message = "lang 은 필수입니다")
    @field:Schema(description = LANG_DESCRIPTION, example = "en", requiredMode = Schema.RequiredMode.REQUIRED)
    val lang: String,

    @field:Schema(description = "다음 페이지 커서(이전 응답의 nextCursor). 생략 시 첫 페이지", example = "42")
    val cursor: String? = null,
)

@Schema(description = "음식별 리뷰 목록 조회 파라미터")
data class ReviewListRequest(
    @field:NotBlank(message = "lang 은 필수입니다")
    @field:Schema(description = LANG_DESCRIPTION, example = "en", requiredMode = Schema.RequiredMode.REQUIRED)
    val lang: String,

    @field:Schema(description = "다음 페이지 커서(이전 응답의 nextCursor). 생략 시 첫 페이지", example = "42")
    val cursor: String? = null,

    @field:Schema(description = "작성 시점 국적 스냅샷 필터(ISO-2 대문자, 정확 일치). 생략 시 전체", example = "VN")
    val countryCode: String? = null,
)

@Schema(description = "내 리뷰 목록 조회 파라미터")
data class MyReviewListRequest(
    @field:NotBlank(message = "lang 은 필수입니다")
    @field:Schema(description = LANG_DESCRIPTION, example = "en", requiredMode = Schema.RequiredMode.REQUIRED)
    val lang: String,

    @field:Schema(description = "다음 페이지 커서(이전 응답의 nextCursor). 생략 시 첫 페이지", example = "42")
    val cursor: String? = null,
)
