package com.kbap.api.processedfood

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.SearchPage
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.ResponseEntity

@Tag(name = "가공식품", description = "국내 가공식품(HACCP 제품 마스터) 검색·상세 API — X-API-Version 3.2 이상")
@SecurityRequirement(name = "bearerAuth")
interface ProcessedFoodApi {
    @Operation(
        summary = "가공식품 검색 (3.2+)",
        description = """
            제품명·제조사로 가공식품을 찾는다. 비회원 사용 가능.
            - 질의는 공백으로 나눈 토큰마다 미러와 같은 정규화(공백·괄호·가운뎃점·'오리지널' 제거)를 한다.
            - 정렬(LLM 없음): 제품명 완전 일치 → 접두 → 포함 → (제품명이 맞는 결과가 하나도 없을 때만) 제조사만 일치. 같은 등급은 맞춘 토큰 수가 많은 순, 짧은 이름 순.
            - 생산종료(최근 미러 실행보다 3일 넘게 안 보인 제품)는 빠진다.
            - nextCursor 는 불투명 문자열 — 그대로 cursor 로 돌려준다. 형식이 맞지 않으면 400 FOOD-002.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "검색 성공"),
            ApiResponse(responseCode = "400", description = "검색어 누락·빈 값(FOOD-003), 커서 형식 불일치(FOOD-002), size 범위 밖"),
        ],
    )
    @ApiErrors(ErrorCode.BLANK_SEARCH_KEYWORD, ErrorCode.INVALID_CURSOR)
    fun search(@ParameterObject request: ProcessedFoodSearchRequest, memberId: Long?): ResponseEntity<BaseResponse<SearchPage<ProcessedFoodSummaryResponse>>>

    @Operation(
        summary = "가공식품 상세 (3.2+)",
        description = """
            원재료·알레르기 원문과 81종 회피 재료 판독 결과를 준다. 비회원 사용 가능(회원이면 avoidance 에 본인 회피 재료 대조가 담긴다).
            - labelSource: DECLARED(알레르기 표시란 있음) · INGREDIENTS_ONLY(표시란이 '알수없음'·빈 값 — 원재료로만 판독) · NONE.
            - 판정은 DETECTED·UNCONFIRMED 뿐이다. UNCONFIRMED 는 '들어 있지 않음'이 아니므로 앱은 '없음·안전'으로 표시하지 않는다.
            - 출처·이미지 고지는 attributionKey 로 내려가고 문구는 앱이 언어별로 정한다. 이미지가 없으면 image 는 null.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "404", description = "없는 제품(PFOOD-001)"),
            ApiResponse(responseCode = "410", description = "생산종료 제품(PFOOD-002)"),
        ],
    )
    @ApiErrors(ErrorCode.PROCESSED_FOOD_NOT_FOUND, ErrorCode.PROCESSED_FOOD_DISCONTINUED)
    fun detail(
        @Parameter(description = "가공식품 id", example = "1201") id: Long,
        memberId: Long?,
    ): ResponseEntity<BaseResponse<ProcessedFoodDetailResponse>>
}
