package com.kbap.api.report

import com.kbap.api.core.ApiHeaders
import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberIdOrNull
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API)
class ReportController(
    private val reportService: ReportService,
) : ReportApi {
    @PostMapping("/reports")
    override fun create(
        @AuthMemberIdOrNull memberId: Long?,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestHeader(name = ApiHeaders.INSTALLATION_ID, required = false) installationId: String?,
        @Valid @RequestBody request: ReportCreateRequest,
    ): ResponseEntity<BaseResponse<Unit>> {
        rejectMalformedAuthorization(memberId, authorization)
        reportService.createReport(
            reporterMemberId = memberId,
            installationId = installationId,
            targetType = request.targetType!!,
            targetId = request.targetId!!,
            reason = request.reason!!,
            detail = request.detail,
        )
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }

    private fun rejectMalformedAuthorization(memberId: Long?, authorization: String?) {
        if (memberId == null && authorization != null) {
            throw BusinessException(ErrorCode.INVALID_ACCESS_TOKEN)
        }
    }
}
