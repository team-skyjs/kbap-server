package com.kbap.api.infra.auth.token

import com.kbap.common.domain.member.model.MemberRole
import io.jsonwebtoken.Jwts
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.util.Date
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

class JwtRefreshTokenRoleTest : BehaviorSpec({
    val secret = "kb118-test-secret-key-at-least-32-bytes-long"
    val properties = JwtTokenProperties(
        secret = secret,
        accessTtl = Duration.ofMinutes(30),
        refreshTtl = Duration.ofDays(14),
        adminAccessTtl = Duration.ofHours(1),
        adminRefreshTtl = Duration.ofDays(7),
    )
    val issuer = JwtTokenIssuer(properties)
    val parser = JwtTokenParser(properties)

    fun expiresInSeconds(token: String): Long {
        val claims = Jwts.parser().verifyWith(SecretKeySpec(secret.toByteArray(), "HmacSHA256")).build()
            .parseSignedClaims(token).payload
        return (claims.expiration.time - claims.issuedAt.time) / 1000
    }

    given("refresh 토큰의 role 클레임") {
        `when`("관리자 역할로 발급하면") {
            then("파싱 결과 role 이 ADMIN 이다") {
                val issued = issuer.issueRefreshToken(memberId = 7L, role = MemberRole.ADMIN)

                parser.parseRefreshToken(issued.token).role shouldBe MemberRole.ADMIN
            }
        }

        `when`("역할 없이 발급하면") {
            then("USER 로 파싱된다") {
                val issued = issuer.issueRefreshToken(memberId = 7L)

                parser.parseRefreshToken(issued.token).role shouldBe MemberRole.USER
            }
        }

        `when`("role 클레임이 없는 구 refresh 토큰을 파싱하면") {
            then("하위 호환으로 USER 로 해석한다") {
                val now = System.currentTimeMillis()
                val legacy = Jwts.builder()
                    .subject("7")
                    .claim(TokenType.CLAIM, TokenType.REFRESH.name)
                    .id(UUID.randomUUID().toString())
                    .issuedAt(Date(now))
                    .expiration(Date(now + 60_000))
                    .signWith(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
                    .compact()

                parser.parseRefreshToken(legacy).role shouldBe MemberRole.USER
            }
        }
    }

    given("관리자 토큰 수명") {
        `when`("ADMIN 역할로 access·refresh 를 발급하면") {
            then("관리자 전용 TTL(1h / 7d)이 적용된다") {
                val access = issuer.issueAccessToken(memberId = 1L, role = MemberRole.ADMIN)
                val refresh = issuer.issueRefreshToken(memberId = 1L, role = MemberRole.ADMIN)

                expiresInSeconds(access) shouldBeInRange 3590L..3610L
                expiresInSeconds(refresh.token) shouldBeInRange (7 * 86400L - 10)..(7 * 86400L + 10)
            }
        }

        `when`("USER 역할로 발급하면") {
            then("기존 회원 TTL(30m / 14d)이 유지된다") {
                val access = issuer.issueAccessToken(memberId = 1L, role = MemberRole.USER)
                val refresh = issuer.issueRefreshToken(memberId = 1L)

                expiresInSeconds(access) shouldBeInRange 1790L..1810L
                expiresInSeconds(refresh.token) shouldBeInRange (14 * 86400L - 10)..(14 * 86400L + 10)
            }
        }
    }
})
