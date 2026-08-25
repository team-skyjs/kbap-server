package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthAdminId
import com.kbap.common.domain.report.model.ReportHandleStatus
import com.kbap.common.domain.report.model.ReportReason
import com.kbap.common.domain.report.model.ReportTargetType
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
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
        @RequestParam(required = false) status: ReportHandleStatus?,
        @RequestParam(required = false) reason: ReportReason?,
        @RequestParam(required = false) targetType: ReportTargetType?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<BaseResponse<AdminReportPageResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminReportService.getReportPage(status, reason, targetType, AdminPaging.page(page), AdminPaging.size(size))))

    @PatchMapping("/{id}")
    override fun handle(
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminReportHandleRequest,
        @AuthAdminId adminId: Long,
    ): ResponseEntity<BaseResponse<AdminReportHandleResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminReportService.handle(adminId, id, request.result!!, request.note)))
}
