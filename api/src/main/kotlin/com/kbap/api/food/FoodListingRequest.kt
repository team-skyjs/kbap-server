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

const val FOOD_LISTING_DESCRIPTION = "명시적 X-API-Version 3.1 이상 계약. sort=default(기본), name_asc, name_desc. " +
    "실제 표시 이름의 로케일 순서와 foodId로 정렬하고 역순은 두 기준 모두 반전한다. " +
    "ICU 77.1, 자연수 비교, 대소문자 동률·악센트 구분, 중국어 간체 pinyin/번체 stroke. " +
    "번역 누락은 한국어 표시명 그대로 비교한다. 기존 전체 기본순은 foodId 내림차순이다. " +
    "모든 정렬에서 nextCursor는 불투명 문자열이다. 구 API의 숫자 커서는 호환되지 않는다. " +
    "페이지 최대 20개, 위험도 필터 탐색 최대 500개. 빈 items라도 hasNext=true면 nextCursor로 계속 조회한다. " +
    "언어·정렬·위험도·회원·목록 종류 전환 시 커서를 버린다. 불일치 400 FOOD-002, " +
    "목록/번역/저장/회피 프로필 변경은 409 FOOD-022이며 첫 페이지부터 다시 조회한다. " +
    "미정의 sort는 400 COMMON-002. 3.0 이하의 기존 숫자 응답 커서는 변경하지 않는다."
