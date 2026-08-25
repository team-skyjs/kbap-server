package com.kbap.api.admin

import com.kbap.api.appversion.AppVersionResponse
import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthAdminId
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/app-version", version = "1.0+")
class AdminAppVersionController(
    private val adminAppVersionService: AdminAppVersionService,
) : AdminAppVersionApi {
    @GetMapping
    override fun getAppVersion(): ResponseEntity<BaseResponse<AppVersionResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminAppVersionService.getAppVersion()))

    @PutMapping
    override fun updateAppVersion(
        @Valid @RequestBody request: AdminAppVersionUpdateRequest,
        @AuthAdminId adminId: Long,
    ): ResponseEntity<BaseResponse<AppVersionResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminAppVersionService.updateAppVersion(adminId, request)))

    @GetMapping("/history")
    override fun getHistory(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<BaseResponse<AdminAuditLogPageResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminAppVersionService.getHistory(AdminPaging.page(page), AdminPaging.size(size))))
}
