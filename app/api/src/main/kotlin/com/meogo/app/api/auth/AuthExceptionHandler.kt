package com.meogo.app.api.auth

import com.meogo.app.api.common.BaseResponse
import com.meogo.application.client.auth.AuthErrorCode
import com.meogo.application.client.auth.AuthException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class AuthExceptionHandler(
    private val authCookieFactory: AuthCookieFactory,
) {
    @ExceptionHandler(AuthException::class)
    fun handleAuth(e: AuthException): ResponseEntity<BaseResponse<Nothing>> {
        val status = HttpStatus.resolve(e.errorCode.status) ?: HttpStatus.UNAUTHORIZED
        val builder = ResponseEntity.status(status)
        if (e.errorCode in SESSION_ENDING_CODES) {
            authCookieFactory.expiredCookies().forEach { builder.header(HttpHeaders.SET_COOKIE, it.toString()) }
        }
        return builder.body(BaseResponse.fail(e.errorCode.message))
    }

    companion object {
        private val SESSION_ENDING_CODES = setOf(
            AuthErrorCode.INVALID_REFRESH_TOKEN,
            AuthErrorCode.EXPIRED_REFRESH_TOKEN,
        )
    }
}
