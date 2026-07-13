package com.meogo.domain.member

import com.meogo.core.lang.CountryCode
import com.meogo.core.lang.LanguageCode

internal data class MemberProfileJson(
    val avoidanceSubstanceCodes: List<String> = emptyList(),
    val spicinessPreference: Int = MemberProfile.DEFAULT_SPICINESS_PREFERENCE,
    val countryCode: String? = null,
    val appLanguage: String? = null,
) {
    fun toDomain(nickname: String?): MemberProfile =
        MemberProfile.of(
            nickname = nickname,
            avoidanceSubstanceCodes = avoidanceSubstanceCodes.map { AvoidanceSubstanceCodeRef(it) }.toSet(),
            spicinessPreference = spicinessPreference,
            countryCode = CountryCode.from(countryCode),
            appLanguage = LanguageCode.entries.firstOrNull { it.code == appLanguage },
        )

    companion object {
        fun from(profile: MemberProfile): MemberProfileJson =
            MemberProfileJson(
                avoidanceSubstanceCodes = profile.avoidanceSubstanceCodes.map { it.value },
                spicinessPreference = profile.spicinessPreference,
                countryCode = profile.countryCode?.name,
                appLanguage = profile.appLanguage?.code,
            )
    }
}
