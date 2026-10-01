package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.JwtAuthenticationFilter
import com.kbap.common.domain.report.model.ReportHandleStatus
import com.kbap.common.domain.report.model.ReportTargetType
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/reports", version = "1.0+")
class AdminReportController(
    private val adminReportService: AdminReportService,
) : AdminReportApi {
    @GetMapping
    override fun getReports(
        @RequestParam(required = false) handleStatus: ReportHandleStatus?,
        @RequestParam(required = false) targetType: ReportTargetType?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): ResponseEntity<BaseResponse<AdminReportPageResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(
                AdminReportPageResponse.from(
                    adminReportService.getReportPage(
                        handleStatus = handleStatus ?: ReportHandleStatus.PENDING,
                        targetType = targetType,
                        page = page?.coerceAtLeast(0) ?: 0,
                        size = size?.coerceIn(1, AdminReportService.DEFAULT_PAGE_SIZE) ?: AdminReportService.DEFAULT_PAGE_SIZE,
                    ),
                ),
            ),
        )

    @PatchMapping("/{reportId}")
    override fun handleReport(
        @PathVariable reportId: Long,
        @RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminAccountId: Long,
        @Valid @RequestBody request: AdminReportHandleRequest,
    ): ResponseEntity<BaseResponse<AdminReportHandleResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(
                AdminReportHandleResponse.from(adminReportService.handleReport(reportId, request.result!!, request.note, adminAccountId)),
            ),
        )

    @PatchMapping("/targets/{targetType}/{targetId}")
    override fun handleTarget(
        @PathVariable targetType: ReportTargetType,
        @PathVariable targetId: Long,
        @RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminAccountId: Long,
        @Valid @RequestBody request: AdminReportHandleRequest,
    ): ResponseEntity<BaseResponse<AdminReportHandleResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(
                AdminReportHandleResponse.from(
                    adminReportService.handleTarget(targetType, targetId, request.result!!, request.note, adminAccountId),
                ),
            ),
        )
}
