package com.kbap.api.food

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.SearchPage
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.ResponseEntity

@Tag(name = "음식")
@SecurityRequirement(name = "bearerAuth")
interface FoodListingApi {
    @Operation(summary = "음식 목록 정렬 조회 (3.1+)", description = DESCRIPTION)
    @ApiErrors(ErrorCode.INVALID_CURSOR, ErrorCode.FOOD_LISTING_STALE)
    fun browse(@ParameterObject request: FoodListingRequest, memberId: Long?): ResponseEntity<BaseResponse<SearchPage<FoodSummaryResponse>>>

    @Operation(summary = "저장 음식 정렬 조회 (3.1+)", description = DESCRIPTION + " 회원 본인의 저장 목록만 조회한다. 기본순은 bookmarkId 내림차순이다.")
    @ApiErrors(ErrorCode.INVALID_CURSOR, ErrorCode.FOOD_LISTING_STALE)
    fun saved(@ParameterObject request: FoodListingRequest, memberId: Long): ResponseEntity<BaseResponse<SearchPage<FoodSummaryResponse>>>

    companion object {
        const val DESCRIPTION = "명시적 X-API-Version 3.1 이상 계약. sort=default(기본), name_asc, name_desc. " +
            "실제 표시 이름의 로케일 순서와 foodId로 정렬하고 역순은 두 기준 모두 반전한다. " +
            "ICU 77.1, 자연수 비교, 대소문자 동률·악센트 구분, 중국어 간체 pinyin/번체 stroke. " +
            "번역 누락은 한국어 표시명 그대로 비교한다. 기존 전체 기본순은 foodId 내림차순이다. " +
            "모든 정렬에서 nextCursor는 불투명 문자열이다. 구 API의 숫자 커서는 호환되지 않는다. " +
            "페이지 최대 20개, 위험도 필터 탐색 최대 500개. 빈 items라도 hasNext=true면 nextCursor로 계속 조회한다. " +
            "언어·정렬·위험도·회원·목록 종류 전환 시 커서를 버린다. 불일치 400 FOOD-002, " +
            "목록/번역/저장/회피 프로필 변경은 409 FOOD-022이며 첫 페이지부터 다시 조회한다. " +
            "미정의 sort는 400 COMMON-002. 3.0 이하의 기존 숫자 응답 커서는 변경하지 않는다."
    }
}
