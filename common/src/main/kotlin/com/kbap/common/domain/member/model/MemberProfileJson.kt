package com.kbap.common.domain.member.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.kbap.common.domain.member.model.CountryCode

@JsonIgnoreProperties("appLanguage")
data class MemberProfileJson(
    val avoidanceSubstanceCodes: List<String> = emptyList(),
    val spicinessPreference: Int = MemberProfile.SPICINESS_UNSET,
    val countryCode: String? = null,
    val profileImageUrl: String? = null,
) {
    fun toDomain(nickname: String?): MemberProfile =
        MemberProfile.of(
            nickname = nickname,
            avoidanceSubstanceCodes = avoidanceSubstanceCodes.map { AvoidanceSubstanceCodeRef(it) }.toSet(),
            spicinessPreference = spicinessPreference,
            countryCode = CountryCode.from(countryCode),
            // 선행 '/' 는 로드 시 제거 — 슬래시로 저장된 legacy 값을 무슬래시 키로 정규화.
            profileImageUrl = profileImageUrl?.trimStart('/'),
        )

    companion object {
        fun from(profile: MemberProfile): MemberProfileJson =
            MemberProfileJson(
                avoidanceSubstanceCodes = profile.avoidanceSubstanceCodes.map { it.value },
                spicinessPreference = profile.spicinessPreference,
                countryCode = profile.countryCode?.name,
                profileImageUrl = profile.profileImageUrl,
            )
    }
}
