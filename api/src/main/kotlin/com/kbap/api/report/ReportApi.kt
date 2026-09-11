package com.kbap.api.report

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "신고", description = "콘텐츠 신고 접수 API — 이번 버전의 신고 대상은 리뷰(REVIEW)뿐이다")
interface ReportApi {
    @Operation(
        summary = "신고 접수(회원·게스트)",
        description = """
            대상 콘텐츠(targetType + targetId)를 사유와 함께 신고한다.
            인증은 선택이다 — 액세스 토큰이 있으면 회원 신고(중복 판정 memberId 기준), 없으면 게스트 신고로
            body 의 installationId 가 필수다(중복 판정 installationId 기준). 회원 신고에서는 installationId 를 무시한다.
            자기 콘텐츠 신고 차단(REPORT-001)은 회원만 적용된다(게스트는 소유 리뷰가 없음).
            접수 후 신고자 본인의 리뷰 목록에서 해당 리뷰가 제외된다(다른 사용자에게는 그대로 노출).
            같은 대상은 신고자당 한 번만 신고할 수 있고 취소 기능은 없다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "접수 성공"),
            ApiResponse(
                responseCode = "400",
                description = "필수 누락·미정의 enum·상세 500자 초과, 자기 콘텐츠 신고(REPORT-001), 게스트인데 installationId 없음(REPORT-004)",
            ),
            ApiResponse(responseCode = "401", description = "액세스 토큰이 있으나 위조·만료(게스트는 토큰 없이 허용)"),
            ApiResponse(responseCode = "404", description = "존재하지 않거나 삭제된 대상(REPORT-003)"),
            ApiResponse(responseCode = "409", description = "이미 신고한 대상(REPORT-002)"),
        ],
    )
    @ApiErrors(
        ErrorCode.REPORT_SELF_TARGET,
        ErrorCode.REPORT_TARGET_NOT_FOUND,
        ErrorCode.REPORT_DUPLICATED,
        ErrorCode.REPORT_INSTALLATION_ID_REQUIRED,
    )
    fun create(memberId: Long?, request: ReportCreateRequest): ResponseEntity<BaseResponse<Unit>>
}
