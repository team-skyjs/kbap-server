package com.kbap.domain.member.model

import com.kbap.core.error.BusinessException
import com.kbap.core.error.ErrorCode
import com.kbap.core.image.ImageUrls
import com.kbap.core.lang.CountryCode
import com.kbap.core.lang.LanguageCode
import com.kbap.domain.avoidance.model.AvoidanceSubstanceCode

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
        require(spicinessPreference == SPICINESS_UNSET || spicinessPreference in SPICINESS_RANGE) {
            "member.profile.spicinessPreference 는 -1(미설정) 또는 0~10 이어야 합니다"
        }
    }

    fun avoidedCodes(): Set<AvoidanceSubstanceCode> =
        avoidanceSubstanceCodes
            .mapNotNull { ref -> AvoidanceSubstanceCode.entries.firstOrNull { it.name == ref.value } }
            .toSet()

    // 검증 있는 copy — 전달된 필드만 검증 후 교체, null 은 기존 값 유지.
    fun updatedWith(
        nickname: String? = null,
        avoidanceSubstanceCodes: List<String>? = null,
        spicinessPreference: Int? = null,
        countryCode: String? = null,
        appLanguage: String? = null,
        profileImageUrl: String? = null,
    ): MemberProfile =
        of(
            nickname = nickname?.let { validatedNickname(it) } ?: this.nickname,
            avoidanceSubstanceCodes = avoidanceSubstanceCodes?.let { validatedCodes(it) }
                ?: this.avoidanceSubstanceCodes,
            spicinessPreference = spicinessPreference?.let { validatedSpiciness(it) }
                ?: this.spicinessPreference,
            countryCode = countryCode?.let { validatedCountry(it) } ?: this.countryCode,
            appLanguage = appLanguage?.let { validatedLanguage(it) } ?: this.appLanguage,
            profileImageUrl = profileImageUrl?.let { validatedImagePath(it) } ?: this.profileImageUrl,
        )

    companion object {
        const val SPICINESS_UNSET: Int = -1

        val SPICINESS_RANGE = 0..10

        private const val PROFILE_IMAGE_PATH_MAX_LENGTH: Int = 512

        private val CATALOG_CODES: Set<String> = AvoidanceSubstanceCode.entries.map { it.name }.toSet()

        // hydration 전용 — 검증은 updatedWith 경유가 유일 경로(무검증 저장 차단)
        internal fun of(
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
                spicinessPreference = SPICINESS_UNSET,
                countryCode = null,
                appLanguage = null,
                profileImageUrl = null,
            )

        private fun validatedNickname(raw: String): String =
            raw.trim().ifBlank { throw BusinessException(ErrorCode.INVALID_NICKNAME) }

        private fun validatedCodes(raw: List<String>): Set<AvoidanceSubstanceCodeRef> {
            if (raw.any { it !in CATALOG_CODES }) {
                throw BusinessException(ErrorCode.INVALID_AVOIDANCE_SUBSTANCE_CODE)
            }
            return raw.map { AvoidanceSubstanceCodeRef(it) }.toSet()
        }

        private fun validatedCountry(raw: String): CountryCode =
            CountryCode.from(raw) ?: throw BusinessException(ErrorCode.INVALID_COUNTRY_CODE)

        private fun validatedLanguage(raw: String): LanguageCode =
            LanguageCode.entries.firstOrNull { it.code == raw }
                ?: throw BusinessException(ErrorCode.UNSUPPORTED_APP_LANGUAGE)

        private fun validatedSpiciness(raw: Int): Int {
            if (raw != SPICINESS_UNSET && raw !in SPICINESS_RANGE) {
                throw BusinessException(ErrorCode.INVALID_SPICINESS_PREFERENCE)
            }
            return raw
        }

        // 저장은 CDN 도메인 없는 경로만 — 빈 문자열·전체 URL 은 거부한다(제거 센티널 없음, 미설정=기본 이미지 경로).
        private fun validatedImagePath(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.length > PROFILE_IMAGE_PATH_MAX_LENGTH || ImageUrls.isAbsoluteUrl(trimmed)) {
                throw BusinessException(ErrorCode.INVALID_PROFILE_IMAGE_URL)
            }
            return trimmed
        }
    }
}
