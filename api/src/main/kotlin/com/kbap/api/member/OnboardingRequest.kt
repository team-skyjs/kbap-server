package com.kbap.api.member

import com.kbap.common.domain.member.dto.MemberProfileInput

data class OnboardingRequest(
    val nickname: String? = null,
    val avoidanceSubstanceCodes: List<String> = emptyList(),
    val countryCode: String,
    val profileImageUrl: String? = null,
    val spicinessPreference: String,
) {
    fun toInput(memberId: Long): MemberProfileInput =
        MemberProfileInput(
            memberId = memberId,
            nickname = nickname,
            avoidanceSubstanceCodes = avoidanceSubstanceCodes,
            countryCode = countryCode,
            profileImageUrl = profileImageUrl,
            spicinessPreference = spicinessPreference,
        )
}
