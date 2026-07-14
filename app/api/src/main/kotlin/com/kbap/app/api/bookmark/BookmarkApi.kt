package com.kbap.app.api.bookmark

import com.kbap.app.api.common.BaseResponse
import com.kbap.app.api.common.Page
import com.kbap.app.api.food.FoodSummaryResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "북마크", description = "음식 북마크 등록·취소·목록 조회 API")
@SecurityRequirement(name = "bearerAuth")
interface BookmarkApi {
    @Operation(
        summary = "음식 북마크 등록",
        description = """
            READY 상태 음식을 북마크한다. 같은 음식을 다시 등록해도 오류 없이 목록에 1건만 유지되며(멱등), 취소했던 음식을 다시 등록하면 목록에 되살아난다.
            존재하지 않거나 아직 미완성(INCOMPLETE)인 음식은 400 으로 거절한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "등록 성공(멱등 — 이미 등록된 음식도 성공)"),
            ApiResponse(responseCode = "400", description = "미존재 또는 미완성 음식"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
        ],
    )
    fun register(
        memberId: Long,
        request: BookmarkCreateRequest,
    ): ResponseEntity<BaseResponse<Unit>>

    @Operation(
        summary = "음식 북마크 취소",
        description = """
            북마크를 취소한다(소프트 삭제 — 행은 남고 목록에서만 사라진다). 등록되지 않은 음식을 취소해도 오류 없이 성공한다(멱등).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "취소 성공(멱등 — 등록되지 않은 음식도 성공)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
        ],
    )
    fun unregister(
        memberId: Long,
        @Parameter(description = "취소할 음식의 안정적 식별자", required = true, example = "1")
        foodId: Long,
    ): ResponseEntity<BaseResponse<Unit>>

    @Operation(
        summary = "음식 북마크 목록 조회 (무한 스크롤, no-offset)",
        description = """
            북마크한 음식을 최근 등록/재등록순으로 한 페이지 20개씩 조회한다. 직전 페이지 nextCursor 를 cursor 로 넘기면 그 이후 20개가 이어진다.
            응답 항목은 음식 요약(음식 목록 API 와 동일 형태)이며 요청 언어(lang)로 표시명을 지역화한다.

            지원 언어: ko(기본), zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es. 미지정/빈/공백은 ko, 지원 목록에 없는 코드는 400.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 최신순 북마크 음식 요약(≤20)·nextCursor·hasNext 반환"),
            ApiResponse(responseCode = "400", description = "잘못된 커서 형식/음수, 또는 지원 목록에 없는 언어 코드"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
        ],
    )
    fun list(
        memberId: Long,
        @Parameter(description = "직전 페이지 nextCursor(마지막 북마크 id). 미지정 시 첫 페이지", required = false, example = "42")
        cursor: String?,
        @Parameter(description = "응답 표시명 언어 코드(미지정/빈/공백 시 ko, 지원 목록에 없는 코드는 400)", required = false, example = "en")
        lang: String?,
    ): ResponseEntity<BaseResponse<Page<FoodSummaryResponse>>>
}
