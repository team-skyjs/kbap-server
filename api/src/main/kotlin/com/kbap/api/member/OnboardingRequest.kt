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
    // serverAssignsProfile(X-API-Version >= 2026.08.07) — 닉네임·사진은 보내도 무시하고 null 로 넘겨 서버가 랜덤 지정.
    // 그 외(미전송·이전 버전·형식 오류)는 종전 계약 그대로 두 필드 필수다(누락 시 400 COMMON-002).
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
