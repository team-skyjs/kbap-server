package com.kbap.domain.member.model

import com.kbap.core.error.BusinessException
import com.kbap.core.error.ErrorCode
import com.kbap.core.lang.CountryCode
import com.kbap.core.lang.LanguageCode
import com.kbap.domain.avoidance.model.AvoidanceSubstanceCode
import java.net.URI
import java.net.URISyntaxException

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

    fun avoidedCodes(): Set<AvoidanceSubstanceCode> =
        avoidanceSubstanceCodes
            .mapNotNull { ref -> AvoidanceSubstanceCode.entries.firstOrNull { it.name == ref.value } }
            .toSet()

    // 검증 있는 copy — 전달된 필드만 검증 후 교체, null 은 기존 값 유지.
    // 사진만 3분법: null=유지 · 값=검증 후 교체 · 빈 문자열=제거(validatedImageUrl 이 null 반환).
    fun updatedWith(
        nickname: String? = null,
        avoidanceSubstanceCodes: List<String>? = null,
        spicinessPreference: Int? = null,
        countryCode: String? = null,
        appLanguage: String? = null,
        profileImageUrl: String? = null,
        allowedImageHosts: List<String>,
    ): MemberProfile =
        of(
            nickname = nickname?.let { validatedNickname(it) } ?: this.nickname,
            avoidanceSubstanceCodes = avoidanceSubstanceCodes?.let { validatedCodes(it) }
                ?: this.avoidanceSubstanceCodes,
            spicinessPreference = spicinessPreference?.let { validatedSpiciness(it) }
                ?: this.spicinessPreference,
            countryCode = countryCode?.let { validatedCountry(it) } ?: this.countryCode,
            appLanguage = appLanguage?.let { validatedLanguage(it) } ?: this.appLanguage,
            profileImageUrl = when (profileImageUrl) {
                null -> this.profileImageUrl
                else -> validatedImageUrl(profileImageUrl, allowedImageHosts)
            },
        )

    companion object {
        const val DEFAULT_SPICINESS_PREFERENCE: Int = 5

        val SPICINESS_RANGE = 0..10

        private const val PROFILE_IMAGE_URL_MAX_LENGTH: Int = 512

        private val CATALOG_CODES: Set<String> = AvoidanceSubstanceCode.entries.map { it.name }.toSet()

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
            if (raw !in SPICINESS_RANGE) {
                throw BusinessException(ErrorCode.INVALID_SPICINESS_PREFERENCE)
            }
            return raw
        }

        // 빈 문자열은 "미설정/제거"(null) — 부분 수정의 미전송(null=유지)과 구분되는 센티널.
        private fun validatedImageUrl(raw: String, allowedImageHosts: List<String>): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.length > PROFILE_IMAGE_URL_MAX_LENGTH) {
                throw BusinessException(ErrorCode.INVALID_PROFILE_IMAGE_URL)
            }
            val host = try {
                URI(trimmed).takeIf { it.scheme.equals("https", ignoreCase = true) }?.host
            } catch (e: URISyntaxException) {
                null
            } ?: throw BusinessException(ErrorCode.INVALID_PROFILE_IMAGE_URL)
            if (allowedImageHosts.isNotEmpty() && host !in allowedImageHosts) {
                throw BusinessException(ErrorCode.INVALID_PROFILE_IMAGE_URL)
            }
            return trimmed
        }
    }
}
