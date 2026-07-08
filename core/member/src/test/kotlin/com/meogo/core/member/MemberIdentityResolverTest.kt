package com.meogo.core.member

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class MemberIdentityResolverTest : BehaviorSpec({

    fun identity(
        provider: SocialProvider = SocialProvider.GOOGLE,
        sub: String = "sub-1",
        email: String? = "user@gmail.com",
    ) = SocialIdentity(provider, sub, email)

    given("기존 신원이 있는 경우") {
        `when`("같은 (provider, providerUserId) 로 해소하면") {
            then("기존 회원을 반환하고 신규가 아니다") {
                val existing = Member.reconstitute(
                    id = 10L,
                    identities = listOf(identity()),
                    profile = MemberProfile.empty(),
                    onboardingStatus = OnboardingStatus.COMPLETED,
                )
                val repository = FakeMemberRepository(existingByIdentity = existing)
                val resolver = MemberIdentityResolver(repository)

                val resolution = resolver.resolve(identity())

                resolution.member shouldBe existing
                resolution.isNewMember shouldBe false
                repository.saveNewCallCount shouldBe 0
            }
        }
    }

    given("신원이 없는 경우") {
        `when`("해소하면") {
            then("신규 회원을 생성하고 신규로 표시하며 온보딩은 PENDING 이다") {
                val repository = FakeMemberRepository()
                val resolver = MemberIdentityResolver(repository)

                val resolution = resolver.resolve(identity())

                resolution.isNewMember shouldBe true
                resolution.member.onboardingStatus shouldBe OnboardingStatus.PENDING
                repository.saveNewCallCount shouldBe 1
            }
        }
    }

    given("동시 최초 로그인 race — saveNew 가 중복 예외") {
        `when`("해소하면") {
            then("재조회 1회로 기존 회원을 반환하고 신규가 아니다") {
                val winner = Member.reconstitute(
                    id = 20L,
                    identities = listOf(identity()),
                    profile = MemberProfile.empty(),
                    onboardingStatus = OnboardingStatus.PENDING,
                )
                val repository = FakeMemberRepository(duplicateOnSaveThenFind = winner)
                val resolver = MemberIdentityResolver(repository)

                val resolution = resolver.resolve(identity())

                resolution.member shouldBe winner
                resolution.isNewMember shouldBe false
            }
        }
    }

    given("같은 email 의 다른 provider 신원") {
        `when`("해소하면") {
            then("email 과 무관하게 별도 신규 회원을 생성한다") {
                val repository = FakeMemberRepository()
                val resolver = MemberIdentityResolver(repository)

                val resolution = resolver.resolve(
                    identity(provider = SocialProvider.APPLE, sub = "apple-sub", email = "user@gmail.com"),
                )

                resolution.isNewMember shouldBe true
                repository.saveNewCallCount shouldBe 1
            }
        }
    }

    given("가입만 하고 온보딩 미완료(PENDING)인 기존 회원") {
        `when`("재로그인으로 해소하면") {
            then("신규가 아니면서 온보딩 상태가 PENDING 으로 노출된다") {
                val pending = Member.reconstitute(
                    id = 30L,
                    identities = listOf(identity()),
                    profile = MemberProfile.empty(),
                    onboardingStatus = OnboardingStatus.PENDING,
                )
                val repository = FakeMemberRepository(existingByIdentity = pending)
                val resolver = MemberIdentityResolver(repository)

                val resolution = resolver.resolve(identity())

                resolution.isNewMember shouldBe false
                resolution.member.onboardingStatus shouldBe OnboardingStatus.PENDING
            }
        }
    }
})

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
            identities = member.identities,
            profile = member.profile,
            onboardingStatus = member.onboardingStatus,
        )
    }

    override fun update(member: Member): Member = member

    override fun withdraw(id: Long) = Unit
}
