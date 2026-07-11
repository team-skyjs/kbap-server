package com.meogo.application.client.member.dto

import com.meogo.core.member.Member

data class MyProfileResult(
    val memberId: Long,
    val nickname: String?,
    val avoidanceSubstanceCodes: List<String>,
    val countryCode: String?,
    val appLanguage: String?,
    val onboardingCompleted: Boolean,
) {
    companion object {
        fun from(member: Member): MyProfileResult =
            MyProfileResult(
                memberId = member.id!!,
                nickname = member.profile.nickname,
                avoidanceSubstanceCodes = member.profile.avoidanceSubstanceCodes.map { it.value },
                countryCode = member.profile.countryCode?.name,
                appLanguage = member.profile.appLanguage?.code,
                onboardingCompleted = member.onboardingCompleted,
            )
    }
}
