package com.kbap.api.food

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

private const val LANG_DESCRIPTION =
    "표시명 언어 코드. 지원: ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. 지원 목록에 없는 값은 en 으로 응답한다."

@Schema(description = "음식 목록 조회 요청")
data class FoodBrowseRequest(
    @field:Schema(description = "직전 페이지 nextCursor(마지막 항목 foodId). 미지정 시 첫 페이지", example = "42")
    val cursor: String? = null,
    @field:NotBlank(message = "lang 은 필수입니다")
    @field:Schema(description = LANG_DESCRIPTION, example = "en", requiredMode = Schema.RequiredMode.REQUIRED)
    val lang: String,
)

@Schema(description = "음식 검색 요청")
data class FoodSearchRequest(
    @field:Schema(description = "검색어(필수, 빈/공백 400). 한국어명 또는 요청 언어 번역명에 부분 일치. 검색어 입력 전 초기 화면은 이 API 가 아니라 스캔 내역 조회를 사용한다", example = "김치")
    val keyword: String? = null,
    @field:Schema(description = "검색 범위 — all(기본, 전체 음식)·scanned(본인 스캔 음식, 회원 전용·최신 스캔순). 그 외 값은 400", example = "all")
    val scope: String? = null,
    @field:Schema(description = "직전 페이지 nextCursor(마지막 항목 foodId). 미지정 시 첫 페이지. scope 간 커서는 호환되지 않는다", example = "42")
    val cursor: String? = null,
    @field:NotBlank(message = "lang 은 필수입니다")
    @field:Schema(description = LANG_DESCRIPTION, example = "en", requiredMode = Schema.RequiredMode.REQUIRED)
    val lang: String,
)

@Schema(description = "음식 상세 조회 요청")
data class FoodDetailRequest(
    @field:NotBlank(message = "lang 은 필수입니다")
    @field:Schema(description = LANG_DESCRIPTION, example = "en", requiredMode = Schema.RequiredMode.REQUIRED)
    val lang: String,
)
