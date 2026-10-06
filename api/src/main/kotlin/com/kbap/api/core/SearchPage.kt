package com.kbap.api.core

import io.swagger.v3.oas.annotations.media.Schema

data class SearchPage<T>(
    val items: List<T>,
    val hasNext: Boolean,
    @field:Schema(description = "다음 페이지 커서 — 불투명 문자열. hasNext 가 false 면 null. 형식은 바뀔 수 있으니 해석하지 말고 그대로 cursor 로 돌려준다", example = "1:3:601", nullable = true)
    val nextCursor: String?,
)
