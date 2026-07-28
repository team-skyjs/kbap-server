package com.kbap.infra.auth.token

import com.kbap.common.port.auth.ParsedAccessToken
import com.kbap.common.port.auth.ParsedRefreshToken
import com.kbap.infra.auth.token.JwtTokenProperties
import com.kbap.common.port.auth.TokenParser
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.error.BusinessException
import com.kbap.common.domain.member.model.MemberRole
import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import org.springframework.stereotype.Component
import javax.crypto.spec.SecretKeySpec

@Component
class JwtTokenParser(
    properties: JwtTokenProperties,
) : TokenParser {
    private val key = SecretKeySpec(properties.secret.toByteArray(), "HmacSHA256")

    override fun parseAccessToken(token: String): ParsedAccessToken {
        val claims = claims(token, ErrorCode.EXPIRED_ACCESS_TOKEN, ErrorCode.INVALID_ACCESS_TOKEN)
        requireTokenType(claims, TokenType.ACCESS, ErrorCode.INVALID_ACCESS_TOKEN)
        val memberId = claims.subject?.toLongOrNull() ?: throw BusinessException(ErrorCode.INVALID_ACCESS_TOKEN)
        val role = (claims[ROLE_CLAIM] as? String)
            ?.let { name -> MemberRole.entries.firstOrNull { it.name == name } }
            ?: throw BusinessException(ErrorCode.INVALID_ACCESS_TOKEN)
        return ParsedAccessToken(memberId = memberId, role = role)
    }

    override fun parseRefreshToken(token: String): ParsedRefreshToken {
        val claims = claims(token, ErrorCode.EXPIRED_REFRESH_TOKEN, ErrorCode.INVALID_REFRESH_TOKEN)
        requireTokenType(claims, TokenType.REFRESH, ErrorCode.INVALID_REFRESH_TOKEN)
        val memberId = claims.subject?.toLongOrNull() ?: throw BusinessException(ErrorCode.INVALID_REFRESH_TOKEN)
        val jti = claims.id ?: throw BusinessException(ErrorCode.INVALID_REFRESH_TOKEN)
        return ParsedRefreshToken(memberId = memberId, jti = jti)
    }

    private fun requireTokenType(claims: Claims, expected: TokenType, onMismatch: ErrorCode) {
        if (claims[TokenType.CLAIM] != expected.name) {
            throw BusinessException(onMismatch)
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
            throw BusinessException(onExpired)
        } catch (e: JwtException) {
            throw BusinessException(onInvalid)
        } catch (e: IllegalArgumentException) {
            throw BusinessException(onInvalid)
        }
}
