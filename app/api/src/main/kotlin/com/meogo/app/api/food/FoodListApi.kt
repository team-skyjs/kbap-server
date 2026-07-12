package com.meogo.app.api.food

import com.meogo.app.api.common.BaseResponse
import com.meogo.app.api.common.Page
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

@Tag(name = "음식 목록", description = "검색어 없이 최신순 음식을 keyset 커서로 조회하는 API")
@SecurityRequirement(name = "bearerAuth")
interface FoodListApi {
    @Operation(
        summary = "음식 목록 조회 (무한 스크롤, no-offset)",
        description = """
            검색어 없이 전체 음식을 최신 등록순(foodId 내림차순)으로 한 페이지 20개씩 조회한다.
            페이지네이션은 offset 이 아니라 no-offset(cursor/keyset) 방식이다 — 직전 페이지 nextCursor 를 cursor 로 넘기면 그 이후(더 오래된) 20개가 이어진다.
            cursor 미지정 시 첫 페이지(최신 20개)로 해석한다. 응답은 다음 커서(nextCursor)와 다음 페이지 존재 여부(hasNext)를 포함한다.

            지원 언어: ko(기본), zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. 미지정/빈/공백은 ko, 지원 목록에 없는 코드는 400.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 최신순 음식 요약(≤20)·nextCursor·hasNext 반환"),
            ApiResponse(responseCode = "400", description = "잘못된 커서 형식/음수, 또는 지원 목록에 없는 언어 코드"),
        ],
    )
    @GetMapping
    fun browse(
        @Parameter(description = "직전 페이지 nextCursor(마지막 항목 foodId). 미지정 시 첫 페이지", required = false, example = "42")
        @RequestParam(required = false) cursor: String?,
        @Parameter(description = "응답 표시명 언어 코드(미지정/빈/공백 시 ko, 지원 목록에 없는 코드는 400)", required = false, example = "en")
        @RequestParam(required = false) lang: String?,
        memberId: Long?,
    ): ResponseEntity<BaseResponse<Page<FoodSummaryResponse>>>
}
