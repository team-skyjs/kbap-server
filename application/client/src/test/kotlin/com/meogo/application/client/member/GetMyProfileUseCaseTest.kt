package com.meogo.application.client.member

import com.meogo.core.kernel.lang.CountryCode
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.member.AvoidanceSubstanceCodeRef
import com.meogo.core.member.Member
import com.meogo.core.member.MemberErrorCode
import com.meogo.core.member.MemberException
import com.meogo.core.member.MemberProfile
import com.meogo.core.member.MemberRepository
import com.meogo.core.member.SocialIdentity
import com.meogo.core.member.SocialProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class GetMyProfileUseCaseTest : BehaviorSpec({

    fun member(id: Long, onboardingCompleted: Boolean, profile: MemberProfile): Member =
        Member.reconstitute(
            id = id,
            identity = SocialIdentity(SocialProvider.GOOGLE, "google-sub-$id", "user$id@gmail.com"),
            profile = profile,
            onboardingCompleted = onboardingCompleted,
        )

    given("온보딩을 완료한 회원") {
        `when`("내 프로필을 조회하면") {
            then("저장된 프로필과 온보딩 완료 상태를 반환한다") {
                val profile = MemberProfile.of(
                    nickname = "길동이",
                    avoidanceSubstanceCodes = setOf(AvoidanceSubstanceCodeRef("EGG"), AvoidanceSubstanceCodeRef("MILK")),
                    spicinessPreference = 5,
                    countryCode = CountryCode.US,
                    appLanguage = LanguageCode.EN,
                )
                val repo = SingleMemberRepository(member(1L, onboardingCompleted = true, profile = profile))
                val useCase = GetMyProfileUseCase(repo)

                val result = useCase.getMyProfile(1L)

                result.memberId shouldBe 1L
                result.nickname shouldBe "길동이"
                result.avoidanceSubstanceCodes.toSet() shouldBe setOf("EGG", "MILK")
                result.countryCode shouldBe "US"
                result.appLanguage shouldBe "en"
                result.onboardingCompleted shouldBe true
            }
        }
    }

    given("온보딩 미완료 회원") {
        `when`("내 프로필을 조회하면") {
            then("빈 프로필과 온보딩 미완료 상태를 반환한다") {
                val repo = SingleMemberRepository(member(1L, onboardingCompleted = false, profile = MemberProfile.empty()))
                val useCase = GetMyProfileUseCase(repo)

                val result = useCase.getMyProfile(1L)

                result.nickname shouldBe null
                result.avoidanceSubstanceCodes.shouldBeEmpty()
                result.countryCode shouldBe null
                result.appLanguage shouldBe null
                result.onboardingCompleted shouldBe false
            }
        }
    }

    given("존재하지 않는 회원") {
        `when`("내 프로필을 조회하면") {
            then("MEMBER_NOT_FOUND 로 거절된다") {
                val repo = SingleMemberRepository(null)
                val useCase = GetMyProfileUseCase(repo)

                val e = shouldThrow<MemberException> { useCase.getMyProfile(99L) }

                e.errorCode shouldBe MemberErrorCode.MEMBER_NOT_FOUND
            }
        }
    }
})

private class SingleMemberRepository(private val member: Member?) : MemberRepository {
    override fun findById(id: Long): Member? = member?.takeIf { it.id == id }

    override fun findByIdentity(provider: SocialProvider, providerUserId: String): Member? = null

    override fun saveNew(member: Member): Member = member

    override fun update(member: Member): Member = member

    override fun withdraw(id: Long) {}
}
