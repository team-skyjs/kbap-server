package com.kbap.api.member

import com.kbap.common.domain.member.model.Member

data class MyProfileResult(
    val memberId: Long,
    val provider: String,
    val nickname: String?,
    val avoidanceSubstanceCodes: List<String>,
    val dietCategories: List<String>,
    val countryCode: String?,
    val profileImageUrl: String?,
    val spicinessPreference: String,
    val currency: String?,
    val onboardingCompleted: Boolean,
    val scanCount: Int,
    val freeScanLimit: Int,
    val scanUnlocked: Boolean,
    val scanRemaining: Int?,
    val ranking: MemberRankingResult,
) {
    companion object {
        fun of(member: Member, ranking: MemberRankingResult, profileImageUrl: String?): MyProfileResult =
            MyProfileResult(
                memberId = member.id,
                provider = member.provider.name,
                nickname = member.profile.nickname,
                avoidanceSubstanceCodes = member.profile.avoidanceSubstanceCodes.map { it.value },
                dietCategories = member.profile.dietCategories.map { it.name },
                countryCode = member.profile.countryCode?.name,
                profileImageUrl = profileImageUrl,
                spicinessPreference = member.profile.spicinessPreference.name,
                currency = member.profile.currency?.name,
                onboardingCompleted = member.onboardingCompleted,
                scanCount = member.scanCount,
                freeScanLimit = Member.FREE_SCAN_LIMIT,
                scanUnlocked = member.scanUnlocked,
                scanRemaining = member.scanRemaining(),
                ranking = ranking,
            )
    }
}
