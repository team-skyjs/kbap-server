package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 알림 테스트 발송", description = "관리자 전용 — 지정 회원의 등록 기기에 NOTICE 푸시를 실제로 보내 발송 파이프라인을 검증한다")
@SecurityRequirement(name = "bearerAuth")
interface AdminNotificationTestApi {
    @Operation(
        summary = "테스트 푸시 발송",
        description = """
            지정 회원의 유효 기기(토큰 무효 처리되지 않은 기기) 전부에 NOTICE 유형 고정 문구("K-Bap" / "테스트 알림입니다.")를
            기기 언어로 발송한다. NOTICE 는 선호 토글·광고성 동의를 보지 않는다.

            - 알림함(`notification`) 행 1개와 기기별 `notification_dispatch` 행이 남고, Expo 티켓 결과가 SENT/FAILED 로 반영된다.
            - 유효 기기가 없으면 발송 없이 `sent=0, failed=0`.
            - dev 실기기 검증용 진입점이다 — 운영 공지 발송 기능이 아니다.
            - **ADMIN 역할 JWT 전용** — USER 토큰은 403(AUTH-008) 으로 거절된다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "발송 시도 완료 — sent/failed 집계"),
            ApiResponse(responseCode = "400", description = "memberId 누락·회원 없음(MEMBER-003)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun sendTestPush(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "발송 대상 회원", required = true)
        request: AdminNotificationTestRequest,
    ): ResponseEntity<BaseResponse<AdminNotificationTestResponse>>
}
