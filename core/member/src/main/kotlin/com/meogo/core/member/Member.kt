package com.meogo.core.member

import com.meogo.core.kernel.stereotype.AggregateRoot

@AggregateRoot
class Member private constructor(
    val id: Long?,
    val identities: List<SocialIdentity>,
    val profile: MemberProfile,
    val onboardingStatus: OnboardingStatus,
) {
    init {
        require(identities.isNotEmpty()) { "member 는 최소 1개의 소셜 신원을 가져야 합니다" }
    }

    fun updateProfile(profile: MemberProfile): Member = copy(profile = profile)

    fun completeOnboarding(): Member {
        if (onboardingStatus == OnboardingStatus.COMPLETED) {
            throw MemberException(MemberErrorCode.ONBOARDING_ALREADY_COMPLETED)
        }
        return copy(onboardingStatus = OnboardingStatus.COMPLETED)
    }

    private fun copy(
        profile: MemberProfile = this.profile,
        onboardingStatus: OnboardingStatus = this.onboardingStatus,
    ): Member =
        Member(
            id = id,
            identities = identities,
            profile = profile,
            onboardingStatus = onboardingStatus,
        )

    companion object {
        fun signUp(identity: SocialIdentity): Member =
            Member(
                id = null,
                identities = listOf(identity),
                profile = MemberProfile.empty(),
                onboardingStatus = OnboardingStatus.PENDING,
            )

        fun reconstitute(
            id: Long,
            identities: List<SocialIdentity>,
            profile: MemberProfile,
            onboardingStatus: OnboardingStatus,
        ): Member =
            Member(
                id = id,
                identities = identities,
                profile = profile,
                onboardingStatus = onboardingStatus,
            )
    }
}
