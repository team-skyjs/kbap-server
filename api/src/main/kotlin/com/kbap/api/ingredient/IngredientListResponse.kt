package com.kbap.api.ingredient

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "재료 카탈로그 목록 응답")
data class IngredientListResponse(
    @field:Schema(description = "재료 전체 목록(id 오름차순)")
    val ingredients: List<IngredientItemResponse>,
)

@Schema(description = "재료 항목")
data class IngredientItemResponse(
    @field:Schema(description = "재료 코드(클라이언트 분기용 안정 식별자)", example = "EGG")
    val code: String,
    @field:Schema(description = "요청 언어 표시명(번역 부재 시 한국어)", example = "계란")
    val name: String,
    @field:Schema(description = "이미지 공개 URL(미매칭 재료는 null)", example = "https://cdn.example.com/images/webp/egg.webp")
    val imageUrl: String?,
    @field:Schema(
        description = "재료가 속한 탐색 분류 코드. 한 재료는 분류 하나에 속하며, 포괄 재료(SEAFOOD·BROTH)는 null 이다. " +
            "분류는 탐색·표시 축이라 위험 판정과 무관하다.",
        example = "CRUSTACEAN",
        nullable = true,
    )
    val categoryCode: String?,
)
