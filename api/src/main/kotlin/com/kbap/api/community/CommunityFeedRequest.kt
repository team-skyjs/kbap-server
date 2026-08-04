package com.kbap.api.community

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "커뮤니티 피드 조회 파라미터")
data class CommunityFeedRequest(
    @field:NotBlank(message = "lang 은 필수입니다")
    @field:Schema(
        description = "표시명 언어 코드. 지원: ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. 지원 목록에 없는 값은 en 으로 응답한다.",
        example = "en",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val lang: String,

    @field:Schema(description = "다음 페이지 커서(이전 응답의 nextCursor). 생략 시 첫 페이지", example = "42")
    val cursor: String? = null,
)

@Schema(description = "커뮤니티 글 상세 조회 파라미터")
data class CommunityPostingDetailRequest(
    @field:NotBlank(message = "lang 은 필수입니다")
    @field:Schema(
        description = "표시명 언어 코드. 지원: ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. 지원 목록에 없는 값은 en 으로 응답한다.",
        example = "en",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val lang: String,
)
