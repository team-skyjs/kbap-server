package com.kbap.api.processedfood

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "가공식품 스캔", description = "제품 앞면 OCR 텍스트로 가공식품을 찾아 확정하는 이름 스캔 — X-API-Version 3.2 이상, 회원 전용")
@SecurityRequirement(name = "bearerAuth")
interface ProcessedFoodNameScanApi {
    @Operation(
        summary = "이름 스캔 (3.2+)",
        description = """
            앞면 OCR 텍스트를 줄·전체 질의로 바꿔(용량·NEW·오리지널 제거, 맛 낱말 유지/제거 두 질의) 가공식품 검색기로 후보 최대 5개를 준다. LLM 없음. **차감 없음.**
            후보가 없으면 state=NO_CANDIDATE — 같은 analysisId 로 성분표 촬영(KB-560)으로 이어간다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "분석 생성"),
            ApiResponse(responseCode = "400", description = "ocrText 누락·2000자 초과"),
            ApiResponse(responseCode = "401", description = "비회원"),
        ],
    )
    fun scanName(memberId: Long, request: ProcessedFoodNameScanRequest): ResponseEntity<BaseResponse<ProcessedFoodNameScanResponse>>

    @Operation(
        summary = "이름 스캔 확정 (3.2+)",
        description = """
            상태 전이:
            | 현재 | 입력 | 결과 | 차감 |
            |---|---|---|---|
            | CANDIDATES | reportNo | CONFIRMED + 상세 | 1 |
            | CANDIDATES | null(해당 없음) | REJECTED | 0 |
            | CONFIRMED | 같은 reportNo | CONFIRMED + 같은 상세(charged=false) | 0 |
            | CONFIRMED | 다른 reportNo·null | 409 PFOOD-004 | 0 |
            | REJECTED | null | REJECTED | 0 |
            | REJECTED·NO_CANDIDATE | reportNo | 409 PFOOD-004 | 0 |
            | 없음·타인·만료(후보 30분·확정 결과 7일) | — | 404 PFOOD-003 | 0 |
            reportNo 는 후보 항목의 reportNo 를 그대로 보낸다 — 후보 밖 제품도 허용(ACTIVE·비생산종료). CONFIRMED 재확정은 그 사이 생산종료돼도 같은 결과를 준다.
            무료 스캔 한도는 메뉴판 스캔과 같은 예약 저장소로 함께 센다 — 남은 횟수가 진행 중인 스캔에 예약돼 있으면 403 SCAN-004.
            같은 분석의 동시·재시도 확정은 분석 행 잠금으로 직렬화되어 한 번만 차감된다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "확정·해당 없음 처리"),
            ApiResponse(responseCode = "403", description = "무료 스캔 소진(SCAN-004)"),
            ApiResponse(responseCode = "404", description = "분석 없음·만료(PFOOD-003), 품목 없음(PFOOD-001)"),
            ApiResponse(responseCode = "409", description = "이미 확정했거나 확정할 수 없는 상태(PFOOD-004)"),
            ApiResponse(responseCode = "410", description = "생산종료 품목(PFOOD-002)"),
        ],
    )
    @ApiErrors(
        ErrorCode.PROCESSED_FOOD_SCAN_NOT_FOUND,
        ErrorCode.PROCESSED_FOOD_SCAN_ALREADY_CONFIRMED,
        ErrorCode.PROCESSED_FOOD_NOT_FOUND,
        ErrorCode.PROCESSED_FOOD_DISCONTINUED,
        ErrorCode.SCAN_LIMIT_EXCEEDED,
        ErrorCode.DUPLICATE_SCAN_REQUEST,
    )
    fun confirm(memberId: Long, request: ProcessedFoodNameConfirmRequest): ResponseEntity<BaseResponse<ProcessedFoodNameConfirmResponse>>
}
