package com.kbap.api.review

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "리뷰 목록 페이지 — 커서가 정렬 지표를 담는 불투명 문자열이라 공용 Page 와 분리")
data class ReviewListPage(
    @field:Schema(description = "리뷰 목록")
    val items: List<ReviewResponse>,

    @field:Schema(description = "다음 페이지 존재 여부")
    val hasNext: Boolean,

    @field:Schema(
        description = "다음 페이지 커서(불투명 문자열 — 그대로 되돌려준다). 발급된 정렬 기준에 종속. 마지막 페이지면 null",
        example = "17_204",
        nullable = true,
    )
    val nextCursor: String? = null,
)
