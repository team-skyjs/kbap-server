package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.common.domain.report.model.ReportHandleStatus
import com.kbap.common.domain.report.model.ReportReason
import com.kbap.common.domain.report.model.ReportTargetType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 신고", description = "신고 큐 — 대상별 누적 건수·처리(기각/콘텐츠 삭제/작성자 정지). 같은 대상의 미처리 신고는 한 번에 처리된다")
@SecurityRequirement(name = "bearerAuth")
interface AdminReportApi {
    @Operation(
        summary = "신고 목록",
        description = "최신순. `target.reportCount` 는 같은 대상에 쌓인 전체 신고 수, `target.exists=false` 면 대상 콘텐츠가 이미 삭제됨. `status` 는 처리 상태(PENDING|HANDLED).",
    )
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회 성공"), ApiResponse(responseCode = "403", description = "AUTH-008")])
    fun getReports(
        @Parameter(description = "처리 상태") status: ReportHandleStatus?,
        reason: ReportReason?,
        targetType: ReportTargetType?,
        page: Int,
        size: Int,
    ): ResponseEntity<BaseResponse<AdminReportPageResponse>>

    @Operation(
        summary = "신고 처리",
        description = """
            `DISMISSED`(기각) · `CONTENT_DELETED`(대상 콘텐츠 소프트 삭제 — 리뷰는 랭킹 차감 포함, 이미 삭제됐으면 건너뜀) · `MEMBER_SUSPENDED`(작성자 정지, `note` 가 정지 사유로 필수).
            같은 대상의 다른 미처리 신고도 같은 결과로 함께 처리되며 `handledReportIds` 로 돌려준다. 이미 처리된 신고면 409(REPORT-004).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "처리 완료"),
            ApiResponse(responseCode = "400", description = "정지 사유 누락(COMMON-002)"),
            ApiResponse(responseCode = "404", description = "없는 신고(REPORT-005) · 정지 대상 작성자를 알 수 없음(REPORT-003)"),
            ApiResponse(responseCode = "409", description = "이미 처리됨(REPORT-004)"),
        ],
    )
    fun handle(id: Long, request: AdminReportHandleRequest, adminId: Long): ResponseEntity<BaseResponse<AdminReportHandleResponse>>
}
