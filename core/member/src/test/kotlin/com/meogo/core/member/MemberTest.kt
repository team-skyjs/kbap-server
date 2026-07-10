package com.meogo.core.member

import com.meogo.core.kernel.lang.CountryCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class MemberTest : BehaviorSpec({

    fun googleIdentity() = SocialIdentity(SocialProvider.GOOGLE, "google-sub-1", "user@gmail.com")

    given("Member.signUp — 최초 가입") {
        `when`("소셜 신원으로 가입하면") {
            then("온보딩 PENDING·빈 프로필·해당 신원을 보유한다") {
                val member = Member.signUp(googleIdentity())

                member.onboardingStatus shouldBe OnboardingStatus.PENDING
                member.profile shouldBe MemberProfile.empty()
                member.identity.providerUserId shouldBe "google-sub-1"
            }
        }
    }

    given("Member.updateProfile — 프로필 갱신(불변)") {
        `when`("새 프로필로 갱신하면") {
            then("새 인스턴스에 값이 반영되고 원본은 유지된다") {
                val member = Member.signUp(googleIdentity())
                val newProfile = MemberProfile.of(
                    nickname = "머고",
                    avoidanceSubstanceCodes = setOf(AvoidanceSubstanceCodeRef("PEANUT")),
                    spicinessPreference = 7,
                    countryCode = CountryCode.KR,
                    appLanguage = null,
                )

                val updated = member.updateProfile(newProfile)

                updated.profile shouldBe newProfile
                member.profile shouldBe MemberProfile.empty()
                updated.identity shouldBe member.identity
                updated.onboardingStatus shouldBe member.onboardingStatus
            }
        }
    }

    given("Member.completeOnboarding — 온보딩 전이") {
        `when`("PENDING 회원이 완료 처리하면") {
            then("COMPLETED 로 전이한 새 인스턴스를 반환한다") {
                val member = Member.signUp(googleIdentity())

                val completed = member.completeOnboarding()

                completed.onboardingStatus shouldBe OnboardingStatus.COMPLETED
                member.onboardingStatus shouldBe OnboardingStatus.PENDING
            }
        }

        `when`("이미 COMPLETED 인 회원이 재완료하면") {
            then("ONBOARDING_ALREADY_COMPLETED 예외를 던진다") {
                val completed = Member.reconstitute(
                    id = 1L,
                    identity = googleIdentity(),
                    profile = MemberProfile.empty(),
                    onboardingStatus = OnboardingStatus.COMPLETED,
                )

                val e = shouldThrow<MemberException> { completed.completeOnboarding() }
                e.errorCode shouldBe MemberErrorCode.ONBOARDING_ALREADY_COMPLETED
            }
        }
    }
})
