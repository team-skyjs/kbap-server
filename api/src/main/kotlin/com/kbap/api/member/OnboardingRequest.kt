package com.kbap.api.member

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.member.dto.MemberProfileInput

data class OnboardingRequest(
    val nickname: String? = null,
    val avoidanceSubstanceCodes: List<String> = emptyList(),
    val countryCode: String,
    val profileImageUrl: String? = null,
    val spicinessPreference: String,
) {
    fun toInput(memberId: Long, serverAssignsProfile: Boolean): MemberProfileInput =
        MemberProfileInput(
            memberId = memberId,
            nickname = if (serverAssignsProfile) null else requireField(nickname),
            avoidanceSubstanceCodes = avoidanceSubstanceCodes,
            countryCode = countryCode,
            profileImageUrl = if (serverAssignsProfile) null else requireField(profileImageUrl),
            spicinessPreference = spicinessPreference,
        )

    private fun requireField(value: String?): String =
        value ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
}
