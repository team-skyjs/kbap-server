package com.kbap.api.member

import com.kbap.common.domain.member.dto.ProfileUpdateInput

data class ProfileUpdateV2Request(
    val nickname: String? = null,
    val avoidanceSubstanceCodes: List<String>? = null,
    val profileImageUrl: String? = null,
    val spicinessPreference: String? = null,
) {
    fun toInput(memberId: Long): ProfileUpdateInput =
        ProfileUpdateInput(
            memberId = memberId,
            nickname = nickname,
            avoidanceSubstanceCodes = avoidanceSubstanceCodes,
            countryCode = null,
            profileImageUrl = profileImageUrl,
            spicinessPreference = spicinessPreference,
        )
}
