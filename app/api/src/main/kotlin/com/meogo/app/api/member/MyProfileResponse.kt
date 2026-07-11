package com.meogo.app.api.member

import com.meogo.application.client.member.dto.MyProfileResult

data class MyProfileResponse(
    val memberId: Long,
    val nickname: String?,
    val avoidanceSubstanceCodes: List<String>,
    val countryCode: String?,
    val appLanguage: String?,
    val onboardingCompleted: Boolean,
) {
    companion object {
        fun from(result: MyProfileResult): MyProfileResponse =
            MyProfileResponse(
                memberId = result.memberId,
                nickname = result.nickname,
                avoidanceSubstanceCodes = result.avoidanceSubstanceCodes,
                countryCode = result.countryCode,
                appLanguage = result.appLanguage,
                onboardingCompleted = result.onboardingCompleted,
            )
    }
}
