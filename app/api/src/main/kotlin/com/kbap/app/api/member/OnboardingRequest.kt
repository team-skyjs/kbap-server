package com.kbap.app.api.member

import com.kbap.domain.member.dto.MemberProfileInput

data class OnboardingRequest(
    val nickname: String,
    val avoidanceSubstanceCodes: List<String> = emptyList(),
    val countryCode: String,
    val appLanguage: String,
    val profileImageUrl: String? = null,
    val spicinessPreference: Int? = null,
) {
    fun toInput(memberId: Long): MemberProfileInput =
        MemberProfileInput(
            memberId = memberId,
            nickname = nickname,
            avoidanceSubstanceCodes = avoidanceSubstanceCodes,
            countryCode = countryCode,
            appLanguage = appLanguage,
            profileImageUrl = profileImageUrl,
            spicinessPreference = spicinessPreference,
        )
}
