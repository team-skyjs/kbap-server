package com.kbap.application.member.dto

import com.kbap.domain.member.Member

data class MyProfileResult(
    val memberId: Long,
    val nickname: String?,
    val avoidanceSubstanceCodes: List<String>,
    val countryCode: String?,
    val appLanguage: String?,
    val onboardingCompleted: Boolean,
    val ranking: MemberRankingResult,
) {
    companion object {
        fun of(member: Member, ranking: MemberRankingResult): MyProfileResult =
            MyProfileResult(
                memberId = member.id!!,
                nickname = member.profile.nickname,
                avoidanceSubstanceCodes = member.profile.avoidanceSubstanceCodes.map { it.value },
                countryCode = member.profile.countryCode?.name,
                appLanguage = member.profile.appLanguage?.code,
                onboardingCompleted = member.onboardingCompleted,
                ranking = ranking,
            )
    }
}
