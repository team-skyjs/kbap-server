package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.report.model.ReportHandleStatus
import com.kbap.common.domain.report.model.ReportTargetType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "어드민 - 신고", description = "신고 대상별 목록과 무시·콘텐츠 삭제 처리")
@SecurityRequirement(name = "bearerAuth")
interface AdminReportApi {
    @Operation(
        summary = "신고 목록(대상별 묶음)",
        description = """
            같은 대상(type + id)에 쌓인 신고를 한 행으로 묶어 최근 신고순으로 내려준다. 재신고·게스트 신고가 모두 한 행에 모이며
            `reporterCount` 는 회원은 회원 id, 게스트는 설치 id 로 중복을 뺀 신고자 수다. `items` 는 펼침용 개별 신고(최근순).
            `handleStatus` 생략 시 **PENDING** — 처리 안 된 신고가 하나라도 있는 대상. `HANDLED` 는 전부 처리된 대상.
            삭제된 대상은 `target.exists = false` 로 남는다.
        """,
    )
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회 성공")])
    fun getReports(
        @Parameter(description = "PENDING·HANDLED. 생략 시 PENDING", example = "PENDING") handleStatus: ReportHandleStatus?,
        @Parameter(description = "대상 타입 필터. 생략 시 전체", example = "REVIEW") targetType: ReportTargetType?,
        @Parameter(description = "0부터", example = "0") page: Int?,
        @Parameter(description = "기본·최대 20", example = "20") size: Int?,
    ): ResponseEntity<BaseResponse<AdminReportPageResponse>>

    @Operation(
        summary = "신고 처리(신고 id 기준)",
        description = """
            그 신고의 **대상에 쌓인 PENDING 신고 전부**를 같은 결과로 HANDLED 처리한다.
            `DISMISSED` 는 콘텐츠를 그대로 두고, `CONTENT_DELETED` 는 리뷰를 소프트 삭제한다(작성자 리뷰 수·랭킹 이벤트도 작성자 삭제와 같게 반영).
            이미 처리된 신고면 409(REPORT-006). 이미 삭제된 대상에 `CONTENT_DELETED` 는 남은 PENDING 만 처리한다(멱등).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "처리 성공"),
            ApiResponse(responseCode = "404", description = "없는 신고(REPORT-007)"),
            ApiResponse(responseCode = "409", description = "이미 처리된 신고(REPORT-006)"),
        ],
    )
    @ApiErrors(ErrorCode.REPORT_NOT_FOUND, ErrorCode.REPORT_ALREADY_HANDLED)
    fun handleReport(
        @Parameter(description = "신고 id", required = true, example = "31") reportId: Long,
        adminAccountId: Long,
        request: AdminReportHandleRequest,
    ): ResponseEntity<BaseResponse<AdminReportHandleResponse>>

    @Operation(
        summary = "신고 처리(대상 기준)",
        description = """
            대상(type + id)에 쌓인 PENDING 신고 전부를 처리한다. 처리할 PENDING 이 없으면 409(REPORT-006) —
            동시에 두 관리자가 같은 대상을 처리하면 하나만 성공하고 나머지는 409 다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "처리 성공"),
            ApiResponse(responseCode = "409", description = "처리할 신고 없음·이미 처리됨(REPORT-006)"),
        ],
    )
    @ApiErrors(ErrorCode.REPORT_ALREADY_HANDLED)
    fun handleTarget(
        @Parameter(description = "대상 타입", required = true, example = "REVIEW") targetType: ReportTargetType,
        @Parameter(description = "대상 id", required = true, example = "17") targetId: Long,
        adminAccountId: Long,
        request: AdminReportHandleRequest,
    ): ResponseEntity<BaseResponse<AdminReportHandleResponse>>
}
