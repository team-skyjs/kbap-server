package com.meogo.core.member

import com.meogo.core.kernel.lang.CountryCode
import com.meogo.core.kernel.lang.LanguageCode

@ConsistentCopyVisibility
data class MemberProfile private constructor(
    val nickname: String?,
    val avoidanceSubstanceCodes: Set<AvoidanceSubstanceCodeRef>,
    val spicinessPreference: Int,
    val countryCode: CountryCode?,
    val appLanguage: LanguageCode?,
) {
    init {
        require(spicinessPreference in SPICINESS_RANGE) { "member.profile.spicinessPreference 는 0~10 이어야 합니다" }
    }

    fun changeNickname(nickname: String?): MemberProfile = copy(nickname = nickname)

    fun changeAvoidanceSubstances(avoidanceSubstanceCodes: Set<AvoidanceSubstanceCodeRef>): MemberProfile =
        copy(avoidanceSubstanceCodes = avoidanceSubstanceCodes)

    fun changeSpicinessPreference(spicinessPreference: Int): MemberProfile =
        copy(spicinessPreference = spicinessPreference)

    fun changeCountry(countryCode: CountryCode?): MemberProfile = copy(countryCode = countryCode)

    fun changeAppLanguage(appLanguage: LanguageCode?): MemberProfile = copy(appLanguage = appLanguage)

    companion object {
        const val DEFAULT_SPICINESS_PREFERENCE: Int = 5

        private val SPICINESS_RANGE = 0..10

        fun of(
            nickname: String?,
            avoidanceSubstanceCodes: Set<AvoidanceSubstanceCodeRef>,
            spicinessPreference: Int,
            countryCode: CountryCode?,
            appLanguage: LanguageCode?,
        ): MemberProfile =
            MemberProfile(
                nickname = nickname,
                avoidanceSubstanceCodes = avoidanceSubstanceCodes,
                spicinessPreference = spicinessPreference,
                countryCode = countryCode,
                appLanguage = appLanguage,
            )

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
