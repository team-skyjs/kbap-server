package com.kbap.api.report

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1)
class ReportController(
    private val reportService: ReportService,
) : ReportApi {
    @PostMapping("/reports")
    override fun create(
        @AuthMemberId memberId: Long,
        @Valid @RequestBody request: ReportCreateRequest,
    ): ResponseEntity<BaseResponse<Unit>> {
        reportService.createReport(
            reporterMemberId = memberId,
            targetType = request.targetType!!,
            targetId = request.targetId!!,
            reason = request.reason!!,
            detail = request.detail,
        )
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }
}
