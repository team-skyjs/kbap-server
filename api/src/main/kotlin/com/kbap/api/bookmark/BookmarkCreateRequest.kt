package com.kbap.api.bookmark

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull

@Schema(description = "음식 북마크 등록 요청")
data class BookmarkCreateRequest(
    @field:NotNull(message = "foodId 는 필수입니다")
    @field:Schema(
        description = "북마크할 음식의 안정적 식별자(음식 목록/검색이 내려준 숫자 id)",
        example = "1",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val foodId: Long?,
)
