package com.kbap.api.food

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

data class FoodListingRequest(
    @field:NotBlank
    @field:Schema(description = "표시 및 정렬 언어. ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. 미지원 값은 en", requiredMode = Schema.RequiredMode.REQUIRED)
    val lang: String,
    @field:Schema(description = "기본순 또는 실제 표시 이름 정순/역순", allowableValues = ["default", "name_asc", "name_desc"], defaultValue = "default")
    val sort: String? = null,
    @field:Schema(description = "직전 nextCursor를 그대로 전달하는 불투명 문자열. 숫자 커서·언어/정렬/필터/회원 불일치: 400 FOOD-002. 목록 변경: 409 FOOD-022, 첫 페이지부터 재조회")
    val cursor: String? = null,
    @field:Schema(description = "위험도 CSV: SAFE, CAUTION, DANGER, UNKNOWN. OR 조건. 빈 중간 페이지도 hasNext=true면 계속 조회")
    val risk: String? = null,
)
