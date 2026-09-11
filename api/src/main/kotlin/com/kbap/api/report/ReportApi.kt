package com.kbap.api.report

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "신고", description = "콘텐츠 신고 접수 API — 이번 버전의 신고 대상은 리뷰(REVIEW)뿐이다")
@SecurityRequirement(name = "bearerAuth")
interface ReportApi {
    @Operation(
        summary = "신고 접수",
        description = """
            대상 콘텐츠(targetType + targetId)를 사유와 함께 신고한다.
            접수 후 신고자 본인의 리뷰 목록에서 해당 리뷰가 제외된다(다른 회원에게는 그대로 노출).
            같은 대상은 한 번만 신고할 수 있고 취소 기능은 없다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "접수 성공"),
            ApiResponse(responseCode = "400", description = "필수 누락·미정의 enum·상세 500자 초과, 자기 콘텐츠 신고(REPORT-001)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
            ApiResponse(responseCode = "404", description = "존재하지 않거나 삭제된 대상(REPORT-003)"),
            ApiResponse(responseCode = "409", description = "이미 신고한 대상(REPORT-002)"),
        ],
    )
        @ApiErrors(
        ErrorCode.REPORT_SELF_TARGET,
        ErrorCode.REPORT_TARGET_NOT_FOUND,
        ErrorCode.REPORT_DUPLICATED,
    )
    fun create(memberId: Long, request: ReportCreateRequest): ResponseEntity<BaseResponse<Unit>>
}
