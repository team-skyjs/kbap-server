package com.meogo.core.member

import com.meogo.core.kernel.lang.CountryCode
import com.meogo.core.kernel.lang.LanguageCode

data class MemberProfile(
    val nickname: String?,
    val avoidanceSubstanceCodes: Set<AvoidanceSubstanceCodeRef>,
    val spicinessPreference: Int,
    val countryCode: CountryCode?,
    val appLanguage: LanguageCode?,
) {
    init {
        require(spicinessPreference in SPICINESS_RANGE) { "member.profile.spicinessPreference 는 0~10 이어야 합니다" }
    }

    companion object {
        const val DEFAULT_SPICINESS_PREFERENCE: Int = 5

        private val SPICINESS_RANGE = 0..10

        fun empty(): MemberProfile =
            MemberProfile(
                nickname = null,
                avoidanceSubstanceCodes = emptySet(),
                spicinessPreference = DEFAULT_SPICINESS_PREFERENCE,
                countryCode = null,
                appLanguage = null,
            )
    }
}
