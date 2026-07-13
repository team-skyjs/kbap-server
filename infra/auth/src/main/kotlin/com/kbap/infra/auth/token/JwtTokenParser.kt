package com.kbap.infra.auth.token

import com.kbap.application.auth.dto.ParsedAccessToken
import com.kbap.application.auth.dto.ParsedRefreshToken
import com.kbap.application.auth.token.AuthTokenProperties
import com.kbap.application.auth.token.TokenParser
import com.kbap.core.error.ErrorCode
import com.kbap.core.error.KbapException
import com.kbap.domain.member.MemberRole
import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import org.springframework.stereotype.Component
import javax.crypto.spec.SecretKeySpec

@Component
class JwtTokenParser(
    properties: AuthTokenProperties,
) : TokenParser {
    private val key = SecretKeySpec(properties.secret.toByteArray(), "HmacSHA256")

    override fun parseAccessToken(token: String): ParsedAccessToken {
        val claims = claims(token, ErrorCode.EXPIRED_ACCESS_TOKEN, ErrorCode.INVALID_ACCESS_TOKEN)
        requireTokenType(claims, TokenType.ACCESS, ErrorCode.INVALID_ACCESS_TOKEN)
        val memberId = claims.subject?.toLongOrNull() ?: throw KbapException(ErrorCode.INVALID_ACCESS_TOKEN)
        val role = (claims[ROLE_CLAIM] as? String)
            ?.let { name -> MemberRole.entries.firstOrNull { it.name == name } }
            ?: throw KbapException(ErrorCode.INVALID_ACCESS_TOKEN)
        return ParsedAccessToken(memberId = memberId, role = role)
    }

    override fun parseRefreshToken(token: String): ParsedRefreshToken {
        val claims = claims(token, ErrorCode.EXPIRED_REFRESH_TOKEN, ErrorCode.INVALID_REFRESH_TOKEN)
        requireTokenType(claims, TokenType.REFRESH, ErrorCode.INVALID_REFRESH_TOKEN)
        val memberId = claims.subject?.toLongOrNull() ?: throw KbapException(ErrorCode.INVALID_REFRESH_TOKEN)
        val jti = claims.id ?: throw KbapException(ErrorCode.INVALID_REFRESH_TOKEN)
        return ParsedRefreshToken(memberId = memberId, jti = jti)
    }

    private fun requireTokenType(claims: Claims, expected: TokenType, onMismatch: ErrorCode) {
        if (claims[TokenType.CLAIM] != expected.name) {
            throw KbapException(onMismatch)
        }
    }

    override fun refreshTokenJtiOrNull(token: String): String? =
        runCatching { Jwts.parser().verifyWith(key).build().parseSignedClaims(token) }
            .fold(
                onSuccess = { it.payload.id },
                onFailure = { (it as? ExpiredJwtException)?.claims?.id },
            )

    private fun claims(token: String, onExpired: ErrorCode, onInvalid: ErrorCode): Claims =
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        } catch (e: ExpiredJwtException) {
            throw KbapException(onExpired)
        } catch (e: JwtException) {
            throw KbapException(onInvalid)
        } catch (e: IllegalArgumentException) {
            throw KbapException(onInvalid)
        }
}
