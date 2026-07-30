package com.kbap.api.review

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "음식별 리뷰 목록 조회 파라미터")
data class ReviewListRequest(
    @field:Schema(description = "다음 페이지 커서(이전 응답의 nextCursor). 생략 시 첫 페이지", example = "42")
    val cursor: String? = null,

    @field:Schema(description = "작성 시점 국적 스냅샷 필터(ISO-2 대문자, 정확 일치). 생략 시 전체", example = "VN")
    val countryCode: String? = null,
)

@Schema(description = "내 리뷰 목록 조회 파라미터")
data class MyReviewListRequest(
    @field:Schema(description = "다음 페이지 커서(이전 응답의 nextCursor). 생략 시 첫 페이지", example = "42")
    val cursor: String? = null,
)
