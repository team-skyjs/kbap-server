package com.kbap.app.api.member

import com.kbap.domain.member.dto.ProfileUpdateInput

data class ProfileUpdateRequest(
    val nickname: String? = null,
    val avoidanceSubstanceCodes: List<String>? = null,
    val countryCode: String? = null,
    val appLanguage: String? = null,
) {
    fun toInput(memberId: Long): ProfileUpdateInput =
        ProfileUpdateInput(
            memberId = memberId,
            nickname = nickname,
            avoidanceSubstanceCodes = avoidanceSubstanceCodes,
            countryCode = countryCode,
            appLanguage = appLanguage,
        )
}
