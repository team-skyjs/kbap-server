package com.kbap.application.auth

import com.kbap.core.error.ErrorCode
import com.kbap.core.error.KbapException
import com.kbap.domain.member.MemberRole
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.Duration

class TokenTest : BehaviorSpec({

    val secret = "kb118-test-secret-key-at-least-32-bytes-long"
    val properties = AuthTokenProperties(
        secret = secret,
        accessTtl = Duration.ofMinutes(30),
        refreshTtl = Duration.ofDays(14),
    )
    val issuer = TokenIssuer(properties)
    val parser = TokenParser(properties)

    given("access 토큰 발급") {
        `when`("회원 식별자와 역할로 발급하면") {
            then("파싱 시 회원 식별자·역할이 복원되고 개인정보 클레임은 담기지 않는다") {
                val token = issuer.issueAccessToken(memberId = 42L, role = MemberRole.USER)

                val parsed = parser.parseAccessToken(token)
                parsed.memberId shouldBe 42L
                parsed.role shouldBe MemberRole.USER
                token.shouldNotBeNull()
            }
        }

        `when`("발급된 토큰 본문을 디코딩하면") {
            then("역할 클레임이 담기고 이메일·닉네임 등 개인정보 문자열은 존재하지 않는다") {
                val token = issuer.issueAccessToken(memberId = 42L, role = MemberRole.USER)
                val payload = String(java.util.Base64.getUrlDecoder().decode(token.split(".")[1]))

                payload shouldContain "\"role\":\"USER\""
                payload shouldNotContain "email"
                payload shouldNotContain "nickname"
            }
        }
    }

    given("access 토큰 역할 클레임 강제") {
        fun accessTokenWithoutRole(roleValue: String? = null): String {
            val now = System.currentTimeMillis()
            val builder = io.jsonwebtoken.Jwts.builder()
                .subject("42")
                .claim(TokenType.CLAIM, TokenType.ACCESS.name)
                .issuedAt(java.util.Date(now))
                .expiration(java.util.Date(now + 60_000))
                .signWith(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            roleValue?.let { builder.claim(ROLE_CLAIM, it) }
            return builder.compact()
        }

        `when`("역할 클레임이 없는 access 토큰을 파싱하면") {
            then("서명이 유효해도 INVALID_ACCESS_TOKEN 으로 거절한다") {
                val e = shouldThrow<KbapException> { parser.parseAccessToken(accessTokenWithoutRole()) }
                e.errorCode shouldBe ErrorCode.INVALID_ACCESS_TOKEN
            }
        }

        `when`("정의되지 않은 역할 값을 담은 access 토큰을 파싱하면") {
            then("INVALID_ACCESS_TOKEN 으로 거절한다") {
                val e = shouldThrow<KbapException> { parser.parseAccessToken(accessTokenWithoutRole("SUPERUSER")) }
                e.errorCode shouldBe ErrorCode.INVALID_ACCESS_TOKEN
            }
        }
    }

    given("refresh 토큰 발급") {
        `when`("회원 식별자로 발급하면") {
            then("jti 와 회원 식별자를 함께 담고 파싱으로 둘 다 복원된다") {
                val issued = issuer.issueRefreshToken(memberId = 7L)

                issued.jti.shouldNotBeNull()
                val parsed = parser.parseRefreshToken(issued.token)
                parsed.memberId shouldBe 7L
                parsed.jti shouldBe issued.jti
            }
        }

        `when`("두 번 발급하면") {
            then("서로 다른 jti 가 발급된다") {
                val first = issuer.issueRefreshToken(memberId = 7L)
                val second = issuer.issueRefreshToken(memberId = 7L)

                (first.jti != second.jti) shouldBe true
            }
        }
    }

    given("조작된 토큰") {
        `when`("다른 시크릿으로 서명한 refresh 토큰을 파싱하면") {
            then("INVALID_REFRESH_TOKEN 예외를 던진다") {
                val forged = TokenIssuer(
                    AuthTokenProperties(
                        secret = "another-secret-key-at-least-32-bytes-long!!",
                        accessTtl = Duration.ofMinutes(30),
                        refreshTtl = Duration.ofDays(14),
                    ),
                ).issueRefreshToken(memberId = 7L).token

                val e = shouldThrow<KbapException> { parser.parseRefreshToken(forged) }
                e.errorCode shouldBe ErrorCode.INVALID_REFRESH_TOKEN
            }
        }

        `when`("본문이 변조된 access 토큰을 파싱하면") {
            then("INVALID_ACCESS_TOKEN 예외를 던진다") {
                val token = issuer.issueAccessToken(memberId = 1L, role = MemberRole.USER)
                val tampered = token.dropLast(3) + "abc"

                val e = shouldThrow<KbapException> { parser.parseAccessToken(tampered) }
                e.errorCode shouldBe ErrorCode.INVALID_ACCESS_TOKEN
            }
        }

        `when`("형식이 아닌 문자열을 파싱하면") {
            then("INVALID_REFRESH_TOKEN 예외를 던진다") {
                val e = shouldThrow<KbapException> { parser.parseRefreshToken("not-a-jwt") }
                e.errorCode shouldBe ErrorCode.INVALID_REFRESH_TOKEN
            }
        }
    }

    given("토큰 타입 강제 — access 와 refresh 는 교차 사용할 수 없다") {
        `when`("refresh 토큰을 access 파서에 넣으면") {
            then("서명이 유효해도 INVALID_ACCESS_TOKEN 으로 거절한다") {
                val refreshToken = issuer.issueRefreshToken(memberId = 7L).token

                val e = shouldThrow<KbapException> { parser.parseAccessToken(refreshToken) }
                e.errorCode shouldBe ErrorCode.INVALID_ACCESS_TOKEN
            }
        }

        `when`("access 토큰을 refresh 파서에 넣으면") {
            then("서명이 유효해도 INVALID_REFRESH_TOKEN 으로 거절한다") {
                val accessToken = issuer.issueAccessToken(memberId = 7L, role = MemberRole.USER)

                val e = shouldThrow<KbapException> { parser.parseRefreshToken(accessToken) }
                e.errorCode shouldBe ErrorCode.INVALID_REFRESH_TOKEN
            }
        }

        `when`("발급된 토큰 본문을 디코딩하면") {
            then("token_type 클레임이 용도별로 박혀 있다") {
                fun payload(token: String) =
                    String(java.util.Base64.getUrlDecoder().decode(token.split(".")[1]))

                payload(issuer.issueAccessToken(1L, MemberRole.USER)) shouldContain "\"token_type\":\"ACCESS\""
                payload(issuer.issueRefreshToken(1L).token) shouldContain "\"token_type\":\"REFRESH\""
            }
        }
    }

    given("만료된 토큰") {
        val expiredIssuer = TokenIssuer(
            AuthTokenProperties(
                secret = secret,
                accessTtl = Duration.ofSeconds(-10),
                refreshTtl = Duration.ofSeconds(-10),
            ),
        )

        `when`("만료된 refresh 토큰을 파싱하면") {
            then("EXPIRED_REFRESH_TOKEN 으로 조작과 구분해 던진다") {
                val expired = expiredIssuer.issueRefreshToken(memberId = 7L).token

                val e = shouldThrow<KbapException> { parser.parseRefreshToken(expired) }
                e.errorCode shouldBe ErrorCode.EXPIRED_REFRESH_TOKEN
            }
        }

        `when`("만료된 access 토큰을 파싱하면") {
            then("EXPIRED_ACCESS_TOKEN 으로 조작과 구분해 던진다") {
                val expired = expiredIssuer.issueAccessToken(memberId = 7L, role = MemberRole.USER)

                val e = shouldThrow<KbapException> { parser.parseAccessToken(expired) }
                e.errorCode shouldBe ErrorCode.EXPIRED_ACCESS_TOKEN
            }
        }
    }

    given("시크릿 길이 제약") {
        `when`("32바이트 미만 시크릿으로 프로퍼티를 만들면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    AuthTokenProperties(
                        secret = "too-short",
                        accessTtl = Duration.ofMinutes(30),
                        refreshTtl = Duration.ofDays(14),
                    )
                }
            }
        }
    }
})
