package com.meogo.application.client.auth

import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import org.springframework.stereotype.Component
import javax.crypto.spec.SecretKeySpec

data class ParsedRefreshToken(
    val memberId: Long,
    val jti: String,
)

@Component
class TokenParser(
    properties: AuthTokenProperties,
) {
    private val key = SecretKeySpec(properties.secret.toByteArray(), "HmacSHA256")

    fun parseAccessToken(token: String): Long {
        val claims = claims(token, AuthErrorCode.EXPIRED_ACCESS_TOKEN, AuthErrorCode.INVALID_ACCESS_TOKEN)
        requireTokenType(claims, TokenType.ACCESS, AuthErrorCode.INVALID_ACCESS_TOKEN)
        return claims.subject?.toLongOrNull() ?: throw AuthException(AuthErrorCode.INVALID_ACCESS_TOKEN)
    }

    fun parseRefreshToken(token: String): ParsedRefreshToken {
        val claims = claims(token, AuthErrorCode.EXPIRED_REFRESH_TOKEN, AuthErrorCode.INVALID_REFRESH_TOKEN)
        requireTokenType(claims, TokenType.REFRESH, AuthErrorCode.INVALID_REFRESH_TOKEN)
        val memberId = claims.subject?.toLongOrNull() ?: throw AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN)
        val jti = claims.id ?: throw AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN)
        return ParsedRefreshToken(memberId = memberId, jti = jti)
    }

    private fun requireTokenType(claims: Claims, expected: TokenType, onMismatch: AuthErrorCode) {
        if (claims[TokenType.CLAIM] != expected.name) {
            throw AuthException(onMismatch)
        }
    }

    fun refreshTokenJtiOrNull(token: String): String? =
        runCatching { Jwts.parser().verifyWith(key).build().parseSignedClaims(token) }
            .fold(
                onSuccess = { it.payload.id },
                onFailure = { (it as? ExpiredJwtException)?.claims?.id },
            )

    private fun claims(token: String, onExpired: AuthErrorCode, onInvalid: AuthErrorCode): Claims =
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        } catch (e: ExpiredJwtException) {
            throw AuthException(onExpired)
        } catch (e: JwtException) {
            throw AuthException(onInvalid)
        } catch (e: IllegalArgumentException) {
            throw AuthException(onInvalid)
        }
}
