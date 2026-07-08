package com.meogo.core.member

import com.meogo.core.kernel.lang.LanguageCode

data class MemberProfile(
    val nickname: String?,
    val avoidanceSubstanceCodes: Set<AvoidanceSubstanceCodeRef>,
    val spicinessPreference: Int?,
    val countryCode: String?,
    val appLanguage: LanguageCode?,
) {
    init {
        spicinessPreference?.let {
            require(it in SPICINESS_RANGE) { "member.profile.spicinessPreference 는 0~10 이어야 합니다" }
        }
    }

    companion object {
        private val SPICINESS_RANGE = 0..10

        fun empty(): MemberProfile =
            MemberProfile(
                nickname = null,
                avoidanceSubstanceCodes = emptySet(),
                spicinessPreference = null,
                countryCode = null,
                appLanguage = null,
            )
    }
}
