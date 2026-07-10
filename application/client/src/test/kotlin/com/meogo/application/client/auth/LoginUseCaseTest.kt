package com.meogo.application.client.auth

import com.meogo.core.member.Member
import com.meogo.core.member.MemberErrorCode
import com.meogo.core.member.MemberException
import com.meogo.core.member.MemberProfile
import com.meogo.core.member.MemberRepository
import com.meogo.core.member.OnboardingStatus
import com.meogo.core.member.RefreshTokenStore
import com.meogo.core.member.SocialIdentity
import com.meogo.core.member.SocialProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration

class LoginUseCaseTest : BehaviorSpec({

    val properties = AuthTokenProperties(
        secret = "kb118-login-usecase-secret-at-least-32-bytes",
        accessTtl = Duration.ofMinutes(30),
        refreshTtl = Duration.ofDays(14),
    )
    val identity = SocialIdentity(SocialProvider.GOOGLE, "google-sub-1", "user@gmail.com")

    fun useCase(
        repository: FakeMemberRepository,
        store: InMemoryRefreshTokenStore,
        verifier: SocialTokenVerifier,
    ) = LoginUseCase(
        socialTokenVerifier = verifier,
        memberRepository = repository,
        tokenIssuer = TokenIssuer(properties),
        refreshTokenStore = store,
        properties = properties,
    )

    given("미가입 사용자의 유효한 소셜 토큰") {
        `when`("로그인하면") {
            then("회원이 생성되고 신규 회원으로 표시되며 토큰 2종이 발급된다") {
                val repository = FakeMemberRepository()
                val store = InMemoryRefreshTokenStore()

                val result = useCase(repository, store, FakeSocialTokenVerifier(identity)).login("id-token")

                result.newMember shouldBe true
                result.memberId shouldBe 99L
                result.accessToken.shouldNotBeNull()
                result.refreshToken.shouldNotBeNull()
                repository.saveNewCallCount shouldBe 1
            }
        }

        `when`("로그인하면") {
            then("발급된 refresh 의 jti 가 저장소에 회원과 함께 등록된다") {
                val repository = FakeMemberRepository()
                val store = InMemoryRefreshTokenStore()

                val result = useCase(repository, store, FakeSocialTokenVerifier(identity)).login("id-token")

                val jti = TokenParser(properties).parseRefreshToken(result.refreshToken).jti
                store.findMemberId(jti) shouldBe 99L
                store.savedTtl shouldBe properties.refreshTtl
            }
        }
    }

    given("이미 가입된 회원의 소셜 토큰") {
        `when`("로그인하면") {
            then("신규 회원이 아니며 중복 가입이 일어나지 않는다") {
                val existing = Member.reconstitute(
                    id = 10L,
                    identity = identity,
                    profile = MemberProfile.empty(),
                    onboardingStatus = OnboardingStatus.COMPLETED,
                )
                val repository = FakeMemberRepository(existingByIdentity = existing)
                val store = InMemoryRefreshTokenStore()

                val result = useCase(repository, store, FakeSocialTokenVerifier(identity)).login("id-token")

                result.newMember shouldBe false
                result.memberId shouldBe 10L
                repository.saveNewCallCount shouldBe 0
            }
        }
    }

    given("검증에 실패하는 소셜 토큰") {
        `when`("로그인하면") {
            then("INVALID_SOCIAL_TOKEN 이 전파되고 회원도 세션도 만들어지지 않는다") {
                val repository = FakeMemberRepository()
                val store = InMemoryRefreshTokenStore()
                val verifier = FailingSocialTokenVerifier(AuthErrorCode.INVALID_SOCIAL_TOKEN)

                val e = shouldThrow<AuthException> { useCase(repository, store, verifier).login("bad-token") }

                e.errorCode shouldBe AuthErrorCode.INVALID_SOCIAL_TOKEN
                repository.saveNewCallCount shouldBe 0
                store.isEmpty() shouldBe true
            }
        }
    }

    given("동시 첫 로그인 경합 — saveNew 가 중복 예외를 던지는 경우") {
        `when`("로그인하면") {
            then("재조회 폴백으로 먼저 가입된 회원을 반환하고 신규가 아니다") {
                val winner = Member.reconstitute(
                    id = 20L,
                    identity = identity,
                    profile = MemberProfile.empty(),
                    onboardingStatus = OnboardingStatus.PENDING,
                )
                val repository = FakeMemberRepository(duplicateOnSaveThenFind = winner)
                val store = InMemoryRefreshTokenStore()

                val result = useCase(repository, store, FakeSocialTokenVerifier(identity)).login("id-token")

                result.newMember shouldBe false
                result.memberId shouldBe 20L
            }
        }
    }

    given("지원하지 않는 provider 토큰") {
        `when`("로그인하면") {
            then("UNSUPPORTED_PROVIDER 가 전파되고 회원이 생성되지 않는다") {
                val repository = FakeMemberRepository()
                val store = InMemoryRefreshTokenStore()
                val verifier = FailingSocialTokenVerifier(AuthErrorCode.UNSUPPORTED_PROVIDER)

                val e = shouldThrow<AuthException> { useCase(repository, store, verifier).login("kakao-token") }

                e.errorCode shouldBe AuthErrorCode.UNSUPPORTED_PROVIDER
                repository.saveNewCallCount shouldBe 0
            }
        }
    }
})

private class FakeSocialTokenVerifier(private val identity: SocialIdentity) : SocialTokenVerifier {
    override fun verify(idToken: String): SocialIdentity = identity
}

private class FailingSocialTokenVerifier(private val errorCode: AuthErrorCode) : SocialTokenVerifier {
    override fun verify(idToken: String): SocialIdentity = throw AuthException(errorCode)
}

private class InMemoryRefreshTokenStore : RefreshTokenStore {
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

    fun isEmpty(): Boolean = sessions.isEmpty()
}

private class FakeMemberRepository(
    private val existingByIdentity: Member? = null,
    private val duplicateOnSaveThenFind: Member? = null,
) : MemberRepository {
    var saveNewCallCount: Int = 0
    private var findAfterDuplicate = false

    override fun findById(id: Long): Member? = null

    override fun findByIdentity(provider: SocialProvider, providerUserId: String): Member? {
        if (existingByIdentity != null) return existingByIdentity
        if (findAfterDuplicate) return duplicateOnSaveThenFind
        return null
    }

    override fun saveNew(member: Member): Member {
        saveNewCallCount++
        if (duplicateOnSaveThenFind != null) {
            findAfterDuplicate = true
            throw MemberException(MemberErrorCode.DUPLICATE_SOCIAL_IDENTITY)
        }
        return Member.reconstitute(
            id = 99L,
            identity = member.identity,
            profile = member.profile,
            onboardingStatus = member.onboardingStatus,
        )
    }

    override fun update(member: Member): Member = member

    override fun withdraw(id: Long) = Unit
}
