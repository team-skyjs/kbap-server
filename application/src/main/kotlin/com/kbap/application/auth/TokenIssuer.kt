package com.kbap.application.auth

import com.kbap.domain.member.MemberRole
import io.jsonwebtoken.Jwts
import org.springframework.stereotype.Component
import java.util.Date
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

const val ROLE_CLAIM: String = "role"

@Component
class TokenIssuer(
    private val properties: AuthTokenProperties,
) {
    private val key = SecretKeySpec(properties.secret.toByteArray(), "HmacSHA256")

    fun issueAccessToken(memberId: Long, role: MemberRole): String {
        val now = System.currentTimeMillis()
        return Jwts.builder()
            .subject(memberId.toString())
            .claim(TokenType.CLAIM, TokenType.ACCESS.name)
            .claim(ROLE_CLAIM, role.name)
            .issuedAt(Date(now))
            .expiration(Date(now + properties.accessTtl.toMillis()))
            .signWith(key)
            .compact()
    }

    fun issueRefreshToken(memberId: Long): IssuedRefreshToken {
        val now = System.currentTimeMillis()
        val jti = UUID.randomUUID().toString()
        val token = Jwts.builder()
            .subject(memberId.toString())
            .claim(TokenType.CLAIM, TokenType.REFRESH.name)
            .id(jti)
            .issuedAt(Date(now))
            .expiration(Date(now + properties.refreshTtl.toMillis()))
            .signWith(key)
            .compact()
        return IssuedRefreshToken(token = token, jti = jti)
    }
}
