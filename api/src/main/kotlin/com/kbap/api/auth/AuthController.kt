package com.kbap.api.auth

import com.kbap.api.core.ApiHeaders
import com.kbap.api.core.ApiPaths
import com.kbap.api.core.ApiVersions
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping(ApiPaths.API + "/auth")
class AuthController(
    private val authService: AuthService,
) : AuthApi {
    @PostMapping("/login")
    override fun login(
        @Valid @RequestBody request: LoginRequest,
        @RequestHeader(ApiHeaders.API_VERSION) apiVersion: String,
        @RequestHeader(ApiHeaders.INSTALLATION_ID, required = false) installationId: String?,
    ): ResponseEntity<BaseResponse<LoginResponse>> {
        val result = authService.login(request.idToken, installationId.takeIf { linksDevice(apiVersion) })
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
        @RequestHeader(ApiHeaders.API_VERSION) apiVersion: String,
        @RequestHeader(ApiHeaders.INSTALLATION_ID, required = false) installationId: String?,
    ): ResponseEntity<BaseResponse<Unit>> {
        authService.logout(request?.refreshToken, installationId.takeIf { linksDevice(apiVersion) })
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }

    @PatchMapping("/withdraw")
    override fun withdraw(
        @AuthMemberId memberId: Long,
        @RequestHeader(ApiHeaders.API_VERSION) apiVersion: String,
    ): ResponseEntity<BaseResponse<Unit>> {
        authService.withdraw(memberId, releaseDevices = linksDevice(apiVersion))
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }

    private fun linksDevice(apiVersion: String): Boolean = ApiVersions.isAtLeast(apiVersion, DEVICE_LINK_VERSION)

    companion object {
        const val DEVICE_LINK_VERSION = "1.1"
    }
}
