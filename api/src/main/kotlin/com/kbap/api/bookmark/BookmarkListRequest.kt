package com.kbap.api.bookmark

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "북마크 목록 조회 요청")
data class BookmarkListRequest(
    @field:Schema(description = "직전 페이지 nextCursor(마지막 북마크 id). 미지정 시 첫 페이지", example = "42")
    val cursor: String? = null,
    @field:NotBlank(message = "lang 은 필수입니다")
    @field:Schema(
        description = "표시명 언어 코드. 지원: ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. 지원 목록에 없는 값은 en 으로 응답한다.",
        example = "en",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val lang: String,
)
