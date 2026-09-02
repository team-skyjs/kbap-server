package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 LLM 레포트", description = "관리자 전용 — 일자별 LLM 호출·비용 상세 레포트 API")
@SecurityRequirement(name = "bearerAuth")
interface AdminLlmReportApi {
    @Operation(
        summary = "일자별 LLM 비용 레포트 조회",
        description = """
            최근 `days`일(기본 7, 1..30)의 일자별 LLM 호출 수·비용(USD)·모델별 상세를 과거→오늘 순으로 반환한다.
            대시보드 핵심 지표와 별개의 운영 상세 레포트다.

            - 각 일자의 `models` 는 비용 내림차순이며, 호출이 없던 날은 빈 배열과 0 값이다.
            - 서버 자체 미터링(LlmCallCost) 기준이다 — Langfuse 연동 지표가 아니다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "400", description = "days 가 1..30 범위 밖(COMMON-002)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun getLlmCostReport(
        @Parameter(description = "조회 일수(1..30, 기본 7)", example = "7")
        @Min(1, message = "days 는 1 이상이어야 합니다")
        @Max(30, message = "days 는 30 이하여야 합니다")
        days: Int,
    ): ResponseEntity<BaseResponse<AdminLlmCostReportResponse>>
}
