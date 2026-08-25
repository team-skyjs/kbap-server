package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.admin.AdminAuditLogFilter
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

@RestController
@RequestMapping(ApiPaths.ADMIN + "/audit-logs", version = "1.0+")
class AdminAuditLogController(
    private val adminAuditQueryService: AdminAuditQueryService,
) : AdminAuditLogApi {
    @GetMapping
    override fun getAuditLogs(
        @RequestParam(required = false) targetType: AdminAuditTargetType?,
        @RequestParam(required = false) targetId: Long?,
        @RequestParam(required = false) adminAccountId: Long?,
        @RequestParam(required = false) action: AdminAuditAction?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: LocalDateTime?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: LocalDateTime?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<BaseResponse<AdminAuditLogPageResponse>> {
        val safePage = AdminPaging.page(page)
        val safeSize = AdminPaging.size(size)
        val filter = AdminAuditLogFilter(targetType, targetId, adminAccountId, action, from, to)
        return ResponseEntity.ok(BaseResponse.ok(adminAuditQueryService.getAuditLogPage(filter, safePage, safeSize)))
    }
}

object AdminPaging {
    const val MAX_SIZE = 200

    fun page(page: Int): Int = if (page < 1) throw BusinessException(ErrorCode.INVALID_REQUEST) else page

    fun size(size: Int): Int = if (size < 1 || size > MAX_SIZE) throw BusinessException(ErrorCode.INVALID_REQUEST) else size
}
