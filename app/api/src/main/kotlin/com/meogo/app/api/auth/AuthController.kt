package com.meogo.app.api.auth

import com.meogo.app.api.common.ApiPaths
import com.meogo.app.api.common.BaseResponse
import com.meogo.application.client.auth.AuthErrorCode
import com.meogo.application.client.auth.AuthException
import com.meogo.application.client.auth.LoginUseCase
import com.meogo.application.client.auth.LogoutUseCase
import com.meogo.application.client.auth.RefreshUseCase
import org.springframework.http.HttpHeaders
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
    private val authCookieFactory: AuthCookieFactory,
) : AuthApi {
    override fun login(
        @RequestBody request: LoginRequest,
    ): ResponseEntity<BaseResponse<LoginResponse>> {
        val result = loginUseCase.login(request.idToken)
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, authCookieFactory.accessCookie(result.accessToken).toString())
            .header(HttpHeaders.SET_COOKIE, authCookieFactory.refreshCookie(result.refreshToken).toString())
            .body(BaseResponse.ok(LoginResponse.from(result)))
    }

    override fun refresh(refreshToken: String?): ResponseEntity<BaseResponse<Unit>> {
        if (refreshToken.isNullOrBlank()) {
            throw AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN)
        }
        val result = refreshUseCase.refresh(refreshToken)
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, authCookieFactory.accessCookie(result.accessToken).toString())
            .header(HttpHeaders.SET_COOKIE, authCookieFactory.refreshCookie(result.refreshToken).toString())
            .body(BaseResponse.ok(Unit))
    }

    override fun logout(refreshToken: String?): ResponseEntity<BaseResponse<Unit>> {
        logoutUseCase.logout(refreshToken)
        return expiredCookies(ResponseEntity.ok()).body(BaseResponse.ok(Unit))
    }

    private fun expiredCookies(builder: ResponseEntity.BodyBuilder): ResponseEntity.BodyBuilder =
        authCookieFactory.expiredCookies()
            .fold(builder) { acc, cookie -> acc.header(HttpHeaders.SET_COOKIE, cookie.toString()) }
}
