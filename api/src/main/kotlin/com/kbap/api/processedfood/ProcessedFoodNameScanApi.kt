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
            앞면 OCR 텍스트를 줄·전체 질의로 바꿔(용량·NEW·오리지널 제거, 맛 낱말 유지/제거 두 질의) 가공식품 검색기로 후보 최대 30개를 한 번에 준다(대표 상품 우선·같은 제품 1칸). LLM 없음. **차감 없음.**
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
            | 현재 | 입력 | 결과 |
            |---|---|---|
            | CANDIDATES | reportNo | CONFIRMED + 상세 |
            | CANDIDATES | null(해당 없음) | REJECTED |
            | CONFIRMED | 같은 reportNo | CONFIRMED + 같은 상세 |
            | CONFIRMED | 다른 reportNo·null | 409 PFOOD-004 |
            | REJECTED | null | REJECTED |
            | REJECTED·NO_CANDIDATE | reportNo | 409 PFOOD-004 |
            | 없음·타인·만료(후보 30분·확정 결과 7일) | — | 404 PFOOD-003 |
            reportNo 는 후보 항목의 reportNo 를 그대로 보낸다 — 후보 밖 제품도 허용(ACTIVE·비생산종료). CONFIRMED 재확정은 그 사이 생산종료돼도 같은 결과를 준다.
            가공식품 스캔은 무료 스캔 횟수에서 차감하지 않는다(KB-963) — 남은 횟수가 0 이어도 확정되고 charged 는 항상 false.
            같은 분석의 동시·재시도 확정은 분석 행 잠금으로 직렬화된다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "확정·해당 없음 처리"),
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
    )
    fun confirm(memberId: Long, request: ProcessedFoodNameConfirmRequest): ResponseEntity<BaseResponse<ProcessedFoodNameConfirmResponse>>

    @Operation(
        summary = "성분표 분석(3.2 이상) — 앱 OCR 원문 여러 면",
        description = """
            앱 온디바이스 OCR 원문 1~4면을 받아 서버 판독기로 알레르기·원재료를 읽는다(서버 OCR·유료 호출 없음).
            문장으로 나눠(줄바꿈·면 경계, 같은 면에서 ','·'('·'·' 로 끝나는 줄만 이음) '알레르기…'·'…함유' 문장은 표시란, '같은 제조시설·혼입·함유 가능…' 문장은 함유 가능(참고만), 나머지는 원재료로 읽는다. 면 사이 같은 줄은 한 번만.
            analysisId 가 없으면 새 분석 — clientRequestId(UUID) 필수(없거나 형식 오류 400 COMMON-002). 같은 키·같은 원문 재전송은 같은 분석을 돌려주고(응답 유실 재시도), 같은 키·다른 원문은 409 SCAN-005. 이름 스캔 NO_CANDIDATE·REJECTED 또는 이전 성분표 결과의 id 면 그 분석에 이어 붙인다(그 외 상태 409 PFOOD-007, 없음·남의·만료 404 PFOOD-003).
            판독 성공은 검출 1개 이상 또는 두 글자 이상 한글 토큰 2개 이상 — 같은 id 재호출은 결과만 갱신(첫 성공 30분 안, 3회까지 — 넘으면 409 PFOOD-007, 앱은 새 분석). 판독 실패는 400 PFOOD-006(이어가기면 이전 결과 유지).
            가공식품 스캔은 무료 스캔 횟수에서 차감하지 않는다(KB-963) — charged 는 항상 false. 검출되지 않은 재료는 UNCONFIRMED — '없음/안전'으로 단정하지 않는다.
        """,
    )
    @ApiErrors(
        ErrorCode.PROCESSED_FOOD_LABEL_NOT_READ,
        ErrorCode.PROCESSED_FOOD_LABEL_NOT_ALLOWED,
        ErrorCode.PROCESSED_FOOD_SCAN_NOT_FOUND,
        ErrorCode.DUPLICATE_SCAN_REQUEST,
        ErrorCode.INVALID_REQUEST,
    )
    fun scanLabel(
        memberId: Long,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "성분표 분석 요청") request: ProcessedFoodLabelScanRequest,
    ): ResponseEntity<BaseResponse<ProcessedFoodLabelScanResponse>>
}
