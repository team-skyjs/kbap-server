package com.kbap.domain.member.model

import com.kbap.core.lang.CountryCode
import com.kbap.core.lang.LanguageCode

@ConsistentCopyVisibility
data class MemberProfile private constructor(
    val nickname: String?,
    val avoidanceSubstanceCodes: Set<AvoidanceSubstanceCodeRef>,
    val spicinessPreference: Int,
    val countryCode: CountryCode?,
    val appLanguage: LanguageCode?,
    val profileImageUrl: String?,
) {
    init {
        require(spicinessPreference in SPICINESS_RANGE) { "member.profile.spicinessPreference 는 0~10 이어야 합니다" }
    }

    companion object {
        const val DEFAULT_SPICINESS_PREFERENCE: Int = 5

        val SPICINESS_RANGE = 0..10

        fun of(
            nickname: String?,
            avoidanceSubstanceCodes: Set<AvoidanceSubstanceCodeRef>,
            spicinessPreference: Int,
            countryCode: CountryCode?,
            appLanguage: LanguageCode?,
            profileImageUrl: String? = null,
        ): MemberProfile =
            MemberProfile(
                nickname = nickname,
                avoidanceSubstanceCodes = avoidanceSubstanceCodes,
                spicinessPreference = spicinessPreference,
                countryCode = countryCode,
                appLanguage = appLanguage,
                profileImageUrl = profileImageUrl,
            )

        fun empty(): MemberProfile =
            MemberProfile(
                nickname = null,
                avoidanceSubstanceCodes = emptySet(),
                spicinessPreference = DEFAULT_SPICINESS_PREFERENCE,
                countryCode = null,
                appLanguage = null,
                profileImageUrl = null,
            )
    }
}
