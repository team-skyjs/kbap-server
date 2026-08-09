package com.kbap.api.member

import com.kbap.common.domain.member.dto.MemberProfileInput

data class OnboardingV2Request(
    val avoidanceSubstanceCodes: List<String> = emptyList(),
    val countryCode: String,
    val spicinessPreference: String,
) {
    // 닉네임·프로필 사진을 넘기지 않아 null 로 남긴다 — MemberService 가 서버 지정값으로 채운다.
    fun toInput(memberId: Long): MemberProfileInput =
        MemberProfileInput(
            memberId = memberId,
            avoidanceSubstanceCodes = avoidanceSubstanceCodes,
            countryCode = countryCode,
            spicinessPreference = spicinessPreference,
        )
}
