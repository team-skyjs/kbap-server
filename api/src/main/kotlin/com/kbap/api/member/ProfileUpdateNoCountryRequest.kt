package com.kbap.api.member

import com.kbap.common.domain.member.dto.ProfileUpdateInput

data class ProfileUpdateNoCountryRequest(
    val nickname: String? = null,
    val avoidanceSubstanceCodes: List<String>? = null,
    val profileImageUrl: String? = null,
    val spicinessPreference: String? = null,
    val currency: String? = null,
) {
    fun toInput(memberId: Long): ProfileUpdateInput =
        ProfileUpdateInput(
            memberId = memberId,
            nickname = nickname,
            avoidanceSubstanceCodes = avoidanceSubstanceCodes,
            countryCode = null,
            profileImageUrl = profileImageUrl,
            spicinessPreference = spicinessPreference,
            currency = currency,
        )
}
