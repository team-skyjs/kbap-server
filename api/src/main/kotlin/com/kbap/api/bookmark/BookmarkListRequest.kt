package com.kbap.api.bookmark

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "북마크 목록 조회 요청")
data class BookmarkListRequest(
    @field:Schema(description = "직전 페이지 nextCursor(마지막 북마크 id). 미지정 시 첫 페이지", example = "42")
    val cursor: String? = null,
    @field:Schema(
        description = "위험도 필터(CSV, 옵션). 값: SAFE·CAUTION·DANGER·UNKNOWN. 여러 값은 OR. " +
            "조회 회원의 회피성분으로 판정한 위험도가 이 집합에 드는 북마크만 내려준다. 요청당 스캔 상한이 있어 items 는 PAGE_SIZE 미만(0 포함)일 수 있고, 종료 판정은 hasNext/nextCursor 로만 한다. 미지정 시 전체. 미정의 값은 400.",
        example = "DANGER,CAUTION",
    )
    val risk: String? = null,
    @field:NotBlank(message = "lang 은 필수입니다")
    @field:Schema(
        description = "표시명 언어 코드. 지원: ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. 지원 목록에 없는 값은 en 으로 응답한다.",
        example = "en",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val lang: String,
)
