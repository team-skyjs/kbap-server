package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 콘텐츠 아웃박스", description = "관리자 전용 — 음식 콘텐츠 수집 요청(랭체인 발행) 아웃박스 조회 API")
@SecurityRequirement(name = "bearerAuth")
interface AdminContentOutboxApi {
    @Operation(
        summary = "콘텐츠 아웃박스 목록 조회",
        description = """
            음식 콘텐츠 수집 요청 아웃박스를 id 내림차순으로 페이지 조회한다. 조회 전용이다 —
            PENDING 재발행은 배치가 자동 수행하고, 새 수집 요청 생성은 재수집(recollect) API 가 담당한다.

            - `status` 를 주면 해당 상태(PENDING·SENT·COMPLETE)만 필터링한다.
            - `q` 를 주면 요청 시점 표시 이름 부분 일치로 검색하고, 숫자면 foodId 일치도 함께 매칭한다.
            - `displayName` 은 수집 요청 시점의 이름 스냅샷이다(음식이 이후 개명·삭제돼도 유지).
            - 페이지 크기는 서버 고정(50)이며, 응답에 전체 건수(`totalCount`)를 포함한다.
            - **ADMIN 역할 JWT 전용** — USER 토큰은 403(AUTH-008) 으로 거절된다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 대상이 없으면 빈 items 와 totalCount=0"),
            ApiResponse(responseCode = "400", description = "status 가 유효한 아웃박스 상태가 아님(COMMON-002)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun getContentOutboxPage(
        @Parameter(description = "1부터 시작하는 페이지 번호(1 미만이면 1로 보정)", example = "1")
        page: Int,
        @Parameter(description = "아웃박스 상태 필터", example = "PENDING")
        status: FoodContentOutboxStatus?,
        @Parameter(description = "표시 이름 부분 일치 검색어(숫자면 foodId 일치도 매칭)", example = "김치")
        q: String?,
    ): ResponseEntity<BaseResponse<AdminContentOutboxPageResponse>>
}
