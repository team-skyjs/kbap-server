package com.meogo.application.client.auth

import com.meogo.core.member.RefreshTokenStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration

class RefreshUseCaseTest : BehaviorSpec({

    val secret = "kb118-refresh-usecase-secret-at-least-32-bytes"
    val properties = AuthTokenProperties(
        secret = secret,
        accessTtl = Duration.ofMinutes(30),
        refreshTtl = Duration.ofDays(14),
    )
    val issuer = TokenIssuer(properties)
    val parser = TokenParser(properties)

    fun useCase(store: RefreshTokenStore) = RefreshUseCase(
        tokenIssuer = issuer,
        tokenParser = parser,
        refreshTokenStore = store,
        properties = properties,
    )

    fun loggedInSession(store: InMemoryStore, memberId: Long = 5L): String {
        val issued = issuer.issueRefreshToken(memberId)
        store.save(issued.jti, memberId, properties.refreshTtl)
        return issued.token
    }

    given("유효한 refresh 토큰") {
        `when`("재발급하면") {
            then("access 와 refresh 를 모두 새로 발급한다") {
                val store = InMemoryStore()
                val refreshToken = loggedInSession(store)

                val result = useCase(store).refresh(refreshToken)

                parser.parseAccessToken(result.accessToken) shouldBe 5L
                parser.parseRefreshToken(result.refreshToken).memberId shouldBe 5L
                (result.refreshToken != refreshToken) shouldBe true
            }
        }

        `when`("재발급하면") {
            then("이전 jti 는 폐기되고 새 jti 가 전체 수명으로 저장된다(rotation)") {
                val store = InMemoryStore()
                val refreshToken = loggedInSession(store)
                val oldJti = parser.parseRefreshToken(refreshToken).jti

                val result = useCase(store).refresh(refreshToken)

                store.findMemberId(oldJti).shouldBeNull()
                val newJti = parser.parseRefreshToken(result.refreshToken).jti
                store.findMemberId(newJti) shouldBe 5L
                store.savedTtl shouldBe properties.refreshTtl
            }
        }

        `when`("재발급 후 이전 refresh 토큰으로 다시 재발급하면") {
            then("INVALID_REFRESH_TOKEN 으로 거절된다") {
                val store = InMemoryStore()
                val refreshToken = loggedInSession(store)
                useCase(store).refresh(refreshToken)

                val e = shouldThrow<AuthException> { useCase(store).refresh(refreshToken) }
                e.errorCode shouldBe AuthErrorCode.INVALID_REFRESH_TOKEN
            }
        }
    }

    given("저장소에 없는 refresh 토큰") {
        `when`("재발급하면") {
            then("INVALID_REFRESH_TOKEN 으로 거절된다") {
                val store = InMemoryStore()
                val orphan = issuer.issueRefreshToken(9L).token

                val e = shouldThrow<AuthException> { useCase(store).refresh(orphan) }
                e.errorCode shouldBe AuthErrorCode.INVALID_REFRESH_TOKEN
            }
        }
    }

    given("조작된 refresh 토큰") {
        `when`("재발급하면") {
            then("INVALID_REFRESH_TOKEN 으로 거절된다") {
                val store = InMemoryStore()
                val forged = TokenIssuer(
                    AuthTokenProperties(
                        secret = "totally-different-secret-at-least-32-bytes",
                        accessTtl = Duration.ofMinutes(30),
                        refreshTtl = Duration.ofDays(14),
                    ),
                ).issueRefreshToken(5L).token

                val e = shouldThrow<AuthException> { useCase(store).refresh(forged) }
                e.errorCode shouldBe AuthErrorCode.INVALID_REFRESH_TOKEN
            }
        }
    }

    given("만료된 refresh 토큰") {
        `when`("재발급하면") {
            then("EXPIRED_REFRESH_TOKEN 으로 거절되고 남은 세션도 폐기된다(강제 로그아웃)") {
                val store = InMemoryStore()
                val expiredIssuer = TokenIssuer(
                    AuthTokenProperties(
                        secret = secret,
                        accessTtl = Duration.ofSeconds(-10),
                        refreshTtl = Duration.ofSeconds(-10),
                    ),
                )
                val expired = expiredIssuer.issueRefreshToken(5L)
                store.save(expired.jti, 5L, properties.refreshTtl)

                val e = shouldThrow<AuthException> { useCase(store).refresh(expired.token) }

                e.errorCode shouldBe AuthErrorCode.EXPIRED_REFRESH_TOKEN
                store.findMemberId(expired.jti).shouldBeNull()
            }
        }
    }
})

private class InMemoryStore : RefreshTokenStore {
    private val sessions = mutableMapOf<String, Long>()
    var savedTtl: Duration? = null

    override fun save(jti: String, memberId: Long, ttl: Duration) {
        sessions[jti] = memberId
        savedTtl = ttl
    }

    override fun consume(jti: String): Long? = sessions.remove(jti)

    fun findMemberId(jti: String): Long? = sessions[jti]

    override fun delete(jti: String) {
        sessions.remove(jti)
    }
}
