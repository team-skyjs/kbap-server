package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 대시보드", description = "관리자 전용 — 어드민 SPA 대시보드 핵심 지표 API")
@SecurityRequirement(name = "bearerAuth")
interface AdminDashboardApi {
    @Operation(
        summary = "대시보드 핵심 지표 조회",
        description = """
            활성 회원 수·검수 대기 건수·주간 스캔 수(전주 대비 비교값 포함)와 최근 7일 스캔 시리즈를 반환한다.

            - `weeklyScans` 는 과거→오늘 순 7개 항목(date·count) — 차트 렌더링은 클라이언트가 담당한다.
            - LLM 비용 지표는 Langfuse 연동 전이라 미포함이다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun getMetricsSummary(): ResponseEntity<BaseResponse<AdminDashboardMetricsResponse>>
}
