package com.kbap.api.infra.auth.token

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.port.scan.IssuedScanTicket
import com.kbap.common.port.scan.ScanTicketCodec
import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@Component
class JwtScanTicketCodec(
    properties: JwtTokenProperties,
    @Value("\${kbap.scan.ticket-ttl-seconds:300}") private val ticketTtlSeconds: Long,
) : ScanTicketCodec {
    private val key = SecretKeySpec(properties.secret.toByteArray(), "HmacSHA256")

    override fun issue(memberId: Long): IssuedScanTicket {
        val now = System.currentTimeMillis()
        val ticket = Jwts.builder()
            .subject(memberId.toString())
            .claim(TokenType.CLAIM, TokenType.SCAN_TICKET.name)
            .id(UUID.randomUUID().toString())
            .issuedAt(Date(now))
            .expiration(Date(now + ticketTtlSeconds * 1000))
            .signWith(key)
            .compact()
        return IssuedScanTicket(ticket = ticket, expiresInSeconds = ticketTtlSeconds)
    }

    override fun verify(ticket: String, memberId: Long): String {
        val claims = claims(ticket)
        if (claims[TokenType.CLAIM] != TokenType.SCAN_TICKET.name) {
            throw BusinessException(ErrorCode.INVALID_SCAN_TICKET)
        }
        if (claims.subject != memberId.toString()) {
            throw BusinessException(ErrorCode.INVALID_SCAN_TICKET)
        }
        return claims.id ?: throw BusinessException(ErrorCode.INVALID_SCAN_TICKET)
    }

    private fun claims(ticket: String): Claims =
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(ticket).payload
        } catch (e: JwtException) {
            throw BusinessException(ErrorCode.INVALID_SCAN_TICKET)
        } catch (e: IllegalArgumentException) {
            throw BusinessException(ErrorCode.INVALID_SCAN_TICKET)
        }
}
