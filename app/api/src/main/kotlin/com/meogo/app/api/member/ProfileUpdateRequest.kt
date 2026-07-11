package com.meogo.app.api.member

import com.meogo.application.client.member.dto.MemberProfileInput

data class ProfileUpdateRequest(
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
