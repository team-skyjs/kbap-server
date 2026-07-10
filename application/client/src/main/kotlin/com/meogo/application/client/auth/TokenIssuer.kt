package com.meogo.application.client.auth

import io.jsonwebtoken.Jwts
import org.springframework.stereotype.Component
import java.util.Date
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

data class IssuedRefreshToken(
    val token: String,
    val jti: String,
)

@Component
class TokenIssuer(
    private val properties: AuthTokenProperties,
) {
    private val key = SecretKeySpec(properties.secret.toByteArray(), "HmacSHA256")

    fun issueAccessToken(memberId: Long): String {
        val now = System.currentTimeMillis()
        return Jwts.builder()
            .subject(memberId.toString())
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
            .id(jti)
            .issuedAt(Date(now))
            .expiration(Date(now + properties.refreshTtl.toMillis()))
            .signWith(key)
            .compact()
        return IssuedRefreshToken(token = token, jti = jti)
    }
}
