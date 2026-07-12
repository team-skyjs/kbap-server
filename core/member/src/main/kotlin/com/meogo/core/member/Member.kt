package com.meogo.core.member

import com.meogo.core.kernel.stereotype.AggregateRoot

@AggregateRoot
class Member private constructor(
    val id: Long?,
    val identity: SocialIdentity,
    val profile: MemberProfile,
    val onboardingCompleted: Boolean,
    val scanCount: Int,
) {
    fun updateProfile(profile: MemberProfile): Member = copy(profile = profile)

    fun completeOnboarding(): Member {
        if (onboardingCompleted) {
            throw MemberException(MemberErrorCode.ONBOARDING_ALREADY_COMPLETED)
        }
        return copy(onboardingCompleted = true)
    }

    fun recordScan(): Member = copy(scanCount = scanCount + 1)

    fun ranking(): MemberRanking =
        MemberRanking.of(
            reviewCount = REVIEW_COUNT_UNAVAILABLE,
            uniqueReviewedFoodCount = REVIEW_COUNT_UNAVAILABLE,
            scanCount = scanCount,
        )

    private fun copy(
        profile: MemberProfile = this.profile,
        onboardingCompleted: Boolean = this.onboardingCompleted,
        scanCount: Int = this.scanCount,
    ): Member =
        Member(
            id = id,
            identity = identity,
            profile = profile,
            onboardingCompleted = onboardingCompleted,
            scanCount = scanCount,
        )

    companion object {
        private const val REVIEW_COUNT_UNAVAILABLE = 0

        fun signUp(identity: SocialIdentity): Member =
            Member(
                id = null,
                identity = identity,
                profile = MemberProfile.empty(),
                onboardingCompleted = false,
                scanCount = 0,
            )

        fun reconstitute(
            id: Long,
            identity: SocialIdentity,
            profile: MemberProfile,
            onboardingCompleted: Boolean,
            scanCount: Int = 0,
        ): Member =
            Member(
                id = id,
                identity = identity,
                profile = profile,
                onboardingCompleted = onboardingCompleted,
                scanCount = scanCount,
            )
    }
}
