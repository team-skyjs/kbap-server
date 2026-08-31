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
)
