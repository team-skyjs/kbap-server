package com.kbap.app.api.auth

import com.kbap.app.api.common.ApiPaths
import com.kbap.app.api.common.BaseResponse
import com.kbap.app.api.common.auth.AuthMemberId
import com.kbap.application.auth.AuthApplicationService
import com.kbap.domain.member.MemberService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/auth")
class AuthController(
    private val authApplicationService: AuthApplicationService,
    private val memberService: MemberService,
) : AuthApi {
    override fun login(
        @RequestBody request: LoginRequest,
    ): ResponseEntity<BaseResponse<LoginResponse>> {
        val result = authApplicationService.login(request.idToken)
        return ResponseEntity.ok(BaseResponse.ok(LoginResponse.from(result)))
    }

    override fun refresh(
        @RequestBody request: RefreshRequest,
    ): ResponseEntity<BaseResponse<TokenResponse>> {
        val result = authApplicationService.refresh(request.refreshToken!!)
        return ResponseEntity.ok(BaseResponse.ok(TokenResponse.from(result)))
    }

    override fun logout(
        @RequestBody(required = false) request: LogoutRequest?,
    ): ResponseEntity<BaseResponse<Unit>> {
        authApplicationService.logout(request?.refreshToken)
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }

    override fun withdraw(
        @AuthMemberId memberId: Long,
    ): ResponseEntity<BaseResponse<Unit>> {
        memberService.withdraw(memberId)
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }
}
