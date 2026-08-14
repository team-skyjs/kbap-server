package com.kbap.api.ingredient

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "diet 카테고리별 회피 재료 매핑 응답")
data class DietListResponse(
    @field:Schema(description = "diet 카테고리 전체 목록(기획 표 순서)")
    val diets: List<DietItemResponse>,
)

@Schema(description = "diet 카테고리 항목")
data class DietItemResponse(
    @field:Schema(description = "카테고리 코드(클라이언트 분기용 안정 식별자)", example = "VEGAN")
    val code: String,
    @field:Schema(description = "카테고리 한국어 표시명", example = "비건")
    val name: String,
    @field:Schema(description = "카테고리에 매핑된 회피 재료 목록(id 오름차순)")
    val ingredients: List<DietIngredientResponse>,
)

@Schema(description = "카테고리에 매핑된 회피 재료")
data class DietIngredientResponse(
    @field:Schema(description = "재료 id", example = "26")
    val id: Long,
    @field:Schema(description = "요청 언어 표시명(번역 부재 시 한국어)", example = "Wheat")
    val name: String,
)
