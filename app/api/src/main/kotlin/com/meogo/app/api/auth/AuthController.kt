package com.meogo.app.api.auth

import com.meogo.app.api.common.ApiPaths
import com.meogo.app.api.common.BaseResponse
import com.meogo.app.api.common.auth.AuthMemberId
import com.meogo.application.auth.LoginUseCase
import com.meogo.application.auth.LogoutUseCase
import com.meogo.application.auth.RefreshUseCase
import com.meogo.application.member.WithdrawUseCase
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/auth")
class AuthController(
    private val loginUseCase: LoginUseCase,
    private val refreshUseCase: RefreshUseCase,
    private val logoutUseCase: LogoutUseCase,
    private val withdrawUseCase: WithdrawUseCase,
) : AuthApi {
    override fun login(
        @RequestBody request: LoginRequest,
    ): ResponseEntity<BaseResponse<LoginResponse>> {
        val result = loginUseCase.login(request.idToken)
        return ResponseEntity.ok(BaseResponse.ok(LoginResponse.from(result)))
    }

    override fun refresh(
        @RequestBody request: RefreshRequest,
    ): ResponseEntity<BaseResponse<TokenResponse>> {
        val result = refreshUseCase.refresh(request.refreshToken!!)
        return ResponseEntity.ok(BaseResponse.ok(TokenResponse.from(result)))
    }

    override fun logout(
        @RequestBody(required = false) request: LogoutRequest?,
    ): ResponseEntity<BaseResponse<Unit>> {
        logoutUseCase.logout(request?.refreshToken)
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }

    override fun withdraw(
        @AuthMemberId memberId: Long,
    ): ResponseEntity<BaseResponse<Unit>> {
        withdrawUseCase.withdraw(memberId)
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }
}
