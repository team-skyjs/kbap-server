package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/auth", version = "1.0+")
class AdminAuthController(
    private val adminAuthService: AdminAuthService,
) : AdminAuthApi {
    @PostMapping("/login")
    override fun login(
        @Valid @RequestBody request: AdminLoginRequest,
    ): ResponseEntity<BaseResponse<AdminTokenResponse>> {
        val tokens = adminAuthService.login(request.id!!, request.password!!)
        return ResponseEntity.ok(BaseResponse.ok(AdminTokenResponse.from(tokens)))
    }

    companion object {
        const val LOGIN_PATH: String = ApiPaths.ADMIN + "/auth/login"
    }
}
