package com.meogo.app.api.food

import com.meogo.app.api.common.BaseResponse
import com.meogo.app.api.common.Page
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

@Tag(name = "음식 검색", description = "검색어로 메뉴를 찾아 최신순 keyset 커서로 조회하는 API")
interface MenuSearchApi {
    @Operation(
        summary = "메뉴 검색 조회 (무한 스크롤, no-offset)",
        description = """
            검색어가 한국어 메뉴명 또는 요청 언어(lang) 번역명에 포함되는 메뉴를 최신 등록순(foodId 내림차순)으로 한 페이지 20개씩 조회한다.
            lang 미지정(ko)이면 한국어명만, 그 외 언어면 한국어명 또는 해당 언어 번역명에 검색어가 포함되면 매칭된다. 대소문자는 구분하지 않는다.
            페이지네이션은 offset 이 아니라 no-offset(cursor/keyset) 방식이다 — 직전 페이지 nextCursor 를 cursor 로 넘기면 그 이후(더 오래된) 20개가 이어진다.
            매칭 메뉴가 없으면 오류가 아니라 빈 목록을 반환한다. 검색어 없이 전체를 훑는 조회는 메뉴 목록 조회 API 를 사용한다.

            지원 언어: ko(기본), zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. 미지정/빈/공백은 ko, 지원 목록에 없는 코드는 400.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 매칭 메뉴 요약(≤20)·nextCursor·hasNext 반환"),
            ApiResponse(responseCode = "400", description = "빈/공백 검색어, 잘못된 커서 형식/음수, 또는 지원 목록에 없는 언어 코드"),
        ],
    )
    @GetMapping("/search")
    fun search(
        @Parameter(description = "검색어(빈/공백 불가). 한국어명 또는 요청 언어 번역명에 부분 일치", required = true, example = "김치")
        @RequestParam(required = false) keyword: String?,
        @Parameter(description = "직전 페이지 nextCursor(마지막 항목 foodId). 미지정 시 첫 페이지", required = false, example = "42")
        @RequestParam(required = false) cursor: String?,
        @Parameter(description = "검색·표시명 언어 코드(미지정/빈/공백 시 ko, 지원 목록에 없는 코드는 400)", required = false, example = "en")
        @RequestParam(required = false) lang: String?,
    ): ResponseEntity<BaseResponse<Page<MenuSummaryResponse>>>
}
