package com.meogo.core.member

import com.meogo.core.kernel.stereotype.AggregateRoot

@AggregateRoot
class Member private constructor(
    val id: Long?,
    val identity: SocialIdentity,
    val profile: MemberProfile,
    val onboardingCompleted: Boolean,
) {
    fun updateProfile(profile: MemberProfile): Member = copy(profile = profile)

    fun completeOnboarding(): Member {
        if (onboardingCompleted) {
            throw MemberException(MemberErrorCode.ONBOARDING_ALREADY_COMPLETED)
        }
        return copy(onboardingCompleted = true)
    }

    private fun copy(
        profile: MemberProfile = this.profile,
        onboardingCompleted: Boolean = this.onboardingCompleted,
    ): Member =
        Member(
            id = id,
            identity = identity,
            profile = profile,
            onboardingCompleted = onboardingCompleted,
        )

    companion object {
        fun signUp(identity: SocialIdentity): Member =
            Member(
                id = null,
                identity = identity,
                profile = MemberProfile.empty(),
                onboardingCompleted = false,
            )

        fun reconstitute(
            id: Long,
            identity: SocialIdentity,
            profile: MemberProfile,
            onboardingCompleted: Boolean,
        ): Member =
            Member(
                id = id,
                identity = identity,
                profile = profile,
                onboardingCompleted = onboardingCompleted,
            )
    }
}
