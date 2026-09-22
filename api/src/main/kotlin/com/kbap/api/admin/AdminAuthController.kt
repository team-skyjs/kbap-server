package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.JwtAuthenticationFilter
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
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

    @GetMapping("/me")
    override fun me(
        @RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminAccountId: Long,
    ): ResponseEntity<BaseResponse<AdminMeResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminLoginService.getMe(adminAccountId)))
}
