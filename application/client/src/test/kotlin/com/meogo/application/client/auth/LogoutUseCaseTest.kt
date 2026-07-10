package com.meogo.application.client.auth

import com.meogo.core.member.RefreshTokenStore
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration

class LogoutUseCaseTest : BehaviorSpec({

    val properties = AuthTokenProperties(
        secret = "kb118-logout-usecase-secret-at-least-32-bytes",
        accessTtl = Duration.ofMinutes(30),
        refreshTtl = Duration.ofDays(14),
    )
    val issuer = TokenIssuer(properties)

    fun useCase(store: RefreshTokenStore) = LogoutUseCase(
        tokenParser = TokenParser(properties),
        refreshTokenStore = store,
    )

    given("로그인된 회원의 refresh 토큰") {
        `when`("로그아웃하면") {
            then("저장된 세션이 폐기된다") {
                val store = InMemorySessions()
                val issued = issuer.issueRefreshToken(3L)
                store.save(issued.jti, 3L, properties.refreshTtl)

                useCase(store).logout(issued.token)

                store.findMemberId(issued.jti).shouldBeNull()
            }
        }
    }

    given("refresh 토큰이 없는 로그아웃") {
        `when`("null 토큰으로 로그아웃하면") {
            then("예외 없이 통과한다(멱등)") {
                val store = InMemorySessions()

                useCase(store).logout(null)

                store.deleteCallCount shouldBe 0
            }
        }

        `when`("조작된 토큰으로 로그아웃하면") {
            then("예외 없이 통과한다(이미 무효한 세션)") {
                val store = InMemorySessions()

                useCase(store).logout("not-a-jwt")

                store.deleteCallCount shouldBe 0
            }
        }
    }

    given("이미 로그아웃한 refresh 토큰") {
        `when`("다시 로그아웃하면") {
            then("예외 없이 통과한다(멱등)") {
                val store = InMemorySessions()
                val issued = issuer.issueRefreshToken(3L)
                store.save(issued.jti, 3L, properties.refreshTtl)
                useCase(store).logout(issued.token)

                useCase(store).logout(issued.token)

                store.findMemberId(issued.jti).shouldBeNull()
            }
        }
    }
})

private class InMemorySessions : RefreshTokenStore {
    private val sessions = mutableMapOf<String, Long>()
    var deleteCallCount: Int = 0

    override fun save(jti: String, memberId: Long, ttl: Duration) {
        sessions[jti] = memberId
    }

    override fun consume(jti: String): Long? = sessions.remove(jti)

    fun findMemberId(jti: String): Long? = sessions[jti]

    override fun delete(jti: String) {
        deleteCallCount++
        sessions.remove(jti)
    }
}
