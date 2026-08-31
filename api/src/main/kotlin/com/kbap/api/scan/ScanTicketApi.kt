package com.kbap.api.scan

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

const val SCAN_TICKET_HEADER = "X-Scan-Ticket"

@Tag(name = "메뉴 스캔 v2", description = "메뉴판 사진 스캔·판정 API — 서버 OCR. 경로 /api/scans + X-API-Version 2.0 이상")
@SecurityRequirement(name = "bearerAuth")
interface ScanTicketApi {
    @Operation(
        summary = "스캔 티켓 발급",
        description = """
            스캔 1회 시도의 자격 증명(서버 서명 티켓)을 발급한다. v2 스캔의 **사전 필수 단계**다 —
            사진 촬영·업로드 전에 호출해, 스캔이 불가능한 상태(무료 3회 소진)를 업로드 비용 없이 미리 확인한다.

            ## 플로우
            1. `POST /api/scans/tickets` — 티켓 발급 (여기서 무료 한도 소진이면 403)
            2. presign 발급 → S3 이미지 업로드 → 업로드 완료 신고
            3. `POST /api/scans` (X-API-Version 2.0) — `X-Scan-Ticket` 헤더에 티켓을 실어 스캔

            ## 규칙
            - 티켓은 **시도 단위**다 — 스캔 시도마다 새로 발급받는다(유효 시간 내 재사용 가능하지만 처리 중 재전송은 409).
            - 티켓은 발급 회원 본인의 스캔에만 유효하다. 위조·만료·타인 티켓은 스캔 시 400(SCAN-007).
            - 응답을 받지 못한 재전송은 **같은 티켓**으로 보낸다 — 처리 중이면 409(SCAN-005)로 이중 소모가 방지된다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "발급 성공 — ticket·expiresInSeconds 반환"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "무료 스캔 3회 소진·리뷰 미작성(SCAN-004) — 리뷰 작성 시 무제한 해제 안내로 분기"),
        ],
    )
    @ApiErrors(ErrorCode.SCAN_LIMIT_EXCEEDED)
    fun issueTicket(memberId: Long): ResponseEntity<BaseResponse<ScanTicketResponse>>
}
