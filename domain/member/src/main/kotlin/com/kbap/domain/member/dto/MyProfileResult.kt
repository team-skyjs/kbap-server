package com.kbap.domain.member.dto

import com.kbap.domain.member.model.Member

data class MyProfileResult(
    val memberId: Long,
    val nickname: String?,
    val avoidanceSubstanceCodes: List<String>,
    val countryCode: String?,
    val appLanguage: String?,
    val profileImageUrl: String?,
    val spicinessPreference: Int,
    val onboardingCompleted: Boolean,
    val ranking: MemberRankingResult,
) {
    companion object {
        fun of(member: Member, ranking: MemberRankingResult, profileImageUrl: String?): MyProfileResult =
            MyProfileResult(
                memberId = member.id,
                nickname = member.profile.nickname,
                avoidanceSubstanceCodes = member.profile.avoidanceSubstanceCodes.map { it.value },
                countryCode = member.profile.countryCode?.name,
                appLanguage = member.profile.appLanguage?.code,
                profileImageUrl = profileImageUrl,
                spicinessPreference = member.profile.spicinessPreference,
                onboardingCompleted = member.onboardingCompleted,
                ranking = ranking,
            )
    }
}
