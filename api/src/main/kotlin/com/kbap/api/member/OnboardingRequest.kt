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
    // serverAssignsProfile(버전 헤더 존재) — 닉네임·사진은 보내도 무시하고 null 로 넘겨 서버가 랜덤 지정.
    // 헤더 없는 구버전 앱 계약은 두 필드 필수 그대로다(누락 시 400 COMMON-002).
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
