package com.kbap.api.member

import com.kbap.common.domain.member.dto.ProfileUpdateInput

data class ProfileUpdateRequest(
    val nickname: String? = null,
    val avoidanceSubstanceCodes: List<String>? = null,
    val countryCode: String? = null,
    val profileImageUrl: String? = null,
    val spicinessPreference: String? = null,
    val currency: String? = null,
) {
    fun toInput(memberId: Long): ProfileUpdateInput =
        ProfileUpdateInput(
            memberId = memberId,
            nickname = nickname,
            avoidanceSubstanceCodes = avoidanceSubstanceCodes,
            countryCode = countryCode,
            profileImageUrl = profileImageUrl,
            spicinessPreference = spicinessPreference,
            currency = currency,
        )
}
