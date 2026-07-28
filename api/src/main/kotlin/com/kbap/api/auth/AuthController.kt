package com.kbap.api.auth

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping(ApiPaths.V1 + "/auth")
class AuthController(
    private val authService: AuthService,
) : AuthApi {
    @PostMapping("/login")
    override fun login(
        @Valid @RequestBody request: LoginRequest,
    ): ResponseEntity<BaseResponse<LoginResponse>> {
        val result = authService.login(request.idToken)
        return ResponseEntity.ok(BaseResponse.ok(LoginResponse.from(result)))
    }

    @PostMapping("/refresh")
    override fun refresh(
        @Valid @RequestBody request: RefreshRequest,
    ): ResponseEntity<BaseResponse<TokenResponse>> {
        val result = authService.refresh(request.refreshToken!!)
        return ResponseEntity.ok(BaseResponse.ok(TokenResponse.from(result)))
    }

    @PostMapping("/logout")
    override fun logout(
        @RequestBody(required = false) request: LogoutRequest?,
    ): ResponseEntity<BaseResponse<Unit>> {
        authService.logout(request?.refreshToken)
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }

    @PatchMapping("/withdraw")
    override fun withdraw(
        @AuthMemberId memberId: Long,
    ): ResponseEntity<BaseResponse<Unit>> {
        authService.withdraw(memberId)
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }
}
