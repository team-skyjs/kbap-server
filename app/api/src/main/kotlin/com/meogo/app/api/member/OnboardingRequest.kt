package com.meogo.app.api.member

import com.meogo.application.client.member.dto.OnboardingInput

data class OnboardingRequest(
    val nickname: String,
    val avoidanceSubstanceCodes: List<String> = emptyList(),
    val countryCode: String,
    val appLanguage: String,
) {
    fun toInput(memberId: Long): OnboardingInput =
        OnboardingInput(
            memberId = memberId,
            nickname = nickname,
            avoidanceSubstanceCodes = avoidanceSubstanceCodes,
            countryCode = countryCode,
            appLanguage = appLanguage,
        )
}
