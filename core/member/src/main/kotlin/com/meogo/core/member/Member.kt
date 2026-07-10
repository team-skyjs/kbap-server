package com.meogo.core.member

import com.meogo.core.kernel.stereotype.AggregateRoot

@AggregateRoot
class Member private constructor(
    val id: Long?,
    val identity: SocialIdentity,
    val profile: MemberProfile,
    val onboardingStatus: OnboardingStatus,
) {
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
            identity = identity,
            profile = profile,
            onboardingStatus = onboardingStatus,
        )

    companion object {
        fun signUp(identity: SocialIdentity): Member =
            Member(
                id = null,
                identity = identity,
                profile = MemberProfile.empty(),
                onboardingStatus = OnboardingStatus.PENDING,
            )

        fun reconstitute(
            id: Long,
            identity: SocialIdentity,
            profile: MemberProfile,
            onboardingStatus: OnboardingStatus,
        ): Member =
            Member(
                id = id,
                identity = identity,
                profile = profile,
                onboardingStatus = onboardingStatus,
            )
    }
}
