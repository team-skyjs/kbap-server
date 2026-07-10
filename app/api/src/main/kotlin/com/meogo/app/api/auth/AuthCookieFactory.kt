package com.meogo.app.api.auth

import com.meogo.app.api.common.ApiPaths
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class AuthCookieFactory(
    @Value("\${meogo.auth.jwt.access-ttl}") private val accessTtl: Duration,
    @Value("\${meogo.auth.jwt.refresh-ttl}") private val refreshTtl: Duration,
    @Value("\${meogo.auth.cookie.secure:false}") private val secure: Boolean,
) {
    fun accessCookie(token: String): ResponseCookie = build(ACCESS_TOKEN, token, ROOT_PATH, accessTtl)

    fun refreshCookie(token: String): ResponseCookie = build(REFRESH_TOKEN, token, AUTH_PATH, refreshTtl)

    fun expiredCookies(): List<ResponseCookie> =
        listOf(
            build(ACCESS_TOKEN, "", ROOT_PATH, Duration.ZERO),
            build(REFRESH_TOKEN, "", AUTH_PATH, Duration.ZERO),
        )

    private fun build(name: String, value: String, path: String, maxAge: Duration): ResponseCookie =
        ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path(path)
            .maxAge(maxAge)
            .build()

    companion object {
        const val ACCESS_TOKEN: String = "access_token"
        const val REFRESH_TOKEN: String = "refresh_token"

        private const val ROOT_PATH = "/"
        private val AUTH_PATH = ApiPaths.V1 + "/auth"
    }
}
