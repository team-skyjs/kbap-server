package com.kbap.app.api.member

import com.kbap.application.member.dto.MemberProfileInput

data class OnboardingRequest(
    val nickname: String,
    val avoidanceSubstanceCodes: List<String> = emptyList(),
    val countryCode: String,
    val appLanguage: String,
) {
    fun toInput(memberId: Long): MemberProfileInput =
        MemberProfileInput(
            memberId = memberId,
            nickname = nickname,
            avoidanceSubstanceCodes = avoidanceSubstanceCodes,
            countryCode = countryCode,
            appLanguage = appLanguage,
        )
}
