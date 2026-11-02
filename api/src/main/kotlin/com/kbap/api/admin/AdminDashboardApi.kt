package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
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

    @Operation(
        summary = "일자별 가입 수",
        description = """
            최근 `days`일(기본 30, 1..90 — 벗어나면 400 COMMON-002)의 일자별 가입 회원 수를 **KST 날짜**로 센다.
            오래된 순 `days`개, 가입이 없는 날은 0. 탈퇴·정지 회원도 가입 시점으로 센다. `isBot` 으로 봇만/사람만.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "400", description = "days 가 1..90 밖(COMMON-002)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun getDailySignups(
        @Parameter(description = "조회 일수(1..90, 기본 30)", example = "30")
        days: Int,
        @Parameter(description = "true 면 봇만, false 면 사람만, 생략하면 전체", example = "false")
        isBot: Boolean?,
    ): ResponseEntity<BaseResponse<List<AdminDailyCountResponse>>>

    @Operation(
        summary = "국가별 스캔 횟수(KB-964)",
        description = """
            회원 국가(country_code)별 스캔 **횟수**(유니크 사용자 아님) — 메뉴판·가공식품 분리 + 합계, total 내림차순, 국가 미설정은 countryCode=null 한 줄. 봇 계정은 항상 제외.
            - 메뉴판: 묶음 스캔(배치)은 성공한 스캔 작업 1건(사진 장수 아님 — 성공 원장 scan_usage 1행), 단일 스캔(v1)은 묶음에 안 묶인 메뉴판 스캔 사진 1장(성공 원장이 없어 업로드 기준 — 실패한 단일 스캔도 센다).
            - 가공식품: 이름 스캔 확정 + 성분표 분석 성공 각 1회(성공 원장 scan_usage 1행 — 해당 없음·판독 실패는 빠진다).
            - days=7|30(KST 자정 기준, 오늘 포함), 생략하면 전체 기간. 그 외 값은 400(COMMON-002).
            - **ADMIN 역할 JWT 전용**.
        """,
    )
    @ApiErrors(ErrorCode.INVALID_REQUEST)
    fun getScanCountries(
        @Parameter(description = "7 또는 30, 생략 = 전체 기간", example = "30")
        days: Int?,
        @Parameter(description = "이 회원들의 스캔은 뺀다 — 쉼표 구분 회원 id 목록", example = "10,12,18")
        excludeMemberIds: List<Long>?,
    ): ResponseEntity<BaseResponse<AdminScanCountryReport>>
}
