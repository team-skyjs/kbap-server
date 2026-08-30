package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.common.domain.food.model.FoodContentStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 음식 카탈로그", description = "관리자 전용 — 어드민 SPA 의 음식 목록/검색 조회 API")
@SecurityRequirement(name = "bearerAuth")
interface AdminFoodCatalogApi {
    @Operation(
        summary = "음식 목록/검색 조회",
        description = """
            전체 음식을 id 내림차순으로 페이지 조회한다. 어드민 SPA 의 음식 카탈로그 목록 화면 전용이다.

            - `q` 를 주면 표시 이름(displayName) 부분 일치로 검색한다.
            - `status` 를 주면 해당 콘텐츠 상태만 필터링한다.
            - 페이지 크기는 서버 고정(200)이며, 응답에 전체 건수(`totalCount`)와 전체 페이지 수를 포함한다.
            - **ADMIN 역할 JWT 전용** — USER 토큰은 403(AUTH-008) 으로 거절된다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 대상이 없으면 빈 items 와 totalCount=0"),
            ApiResponse(responseCode = "400", description = "status 가 유효한 콘텐츠 상태가 아님(COMMON-002)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun getFoodPage(
        @Parameter(description = "1부터 시작하는 페이지 번호(1 미만이면 1로 보정)", example = "1")
        page: Int,
        @Parameter(description = "표시 이름 부분 일치 검색어", example = "김치")
        q: String?,
        @Parameter(description = "콘텐츠 상태 필터", example = "READY")
        status: FoodContentStatus?,
    ): ResponseEntity<BaseResponse<AdminFoodListResponse>>
}
