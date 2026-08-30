package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/auth", version = "1.0+")
class AdminAuthController(
    private val adminLoginService: AdminLoginService,
) : AdminAuthApi {
    @PostMapping("/login")
    override fun login(
        @Valid @RequestBody request: AdminLoginRequest,
    ): ResponseEntity<BaseResponse<AdminLoginResponse>> {
        val token = adminLoginService.login(request.id!!, request.password!!)
            ?: throw BusinessException(ErrorCode.ADMIN_LOGIN_FAILED)
        return ResponseEntity.ok(BaseResponse.ok(AdminLoginResponse(token)))
    }
}
