package com.kbap.common.domain.member.model

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.util.ImageUrls
import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.domain.member.model.CountryCode

@ConsistentCopyVisibility
data class MemberProfile private constructor(
    val nickname: String?,
    val avoidanceSubstanceCodes: Set<AvoidedIngredientCodeRef>,
    val spicinessPreference: SpicinessPreference,
    val countryCode: CountryCode?,
    val profileImageUrl: String?,
) {
    fun avoidedCodes(): Set<IngredientCode> =
        avoidanceSubstanceCodes
            .mapNotNull { ref -> IngredientCode.entries.firstOrNull { it.name == ref.value } }
            .toSet()

    // 검증 있는 copy — 전달된 필드만 검증 후 교체, null 은 기존 값 유지.
    fun updatedWith(
        nickname: String? = null,
        avoidanceSubstanceCodes: List<String>? = null,
        spicinessPreference: String? = null,
        countryCode: String? = null,
        profileImageUrl: String? = null,
    ): MemberProfile =
        of(
            nickname = nickname?.let { validatedNickname(it) } ?: this.nickname,
            avoidanceSubstanceCodes = avoidanceSubstanceCodes?.let { validatedCodes(it) }
                ?: this.avoidanceSubstanceCodes,
            spicinessPreference = spicinessPreference?.let { validatedSpiciness(it) }
                ?: this.spicinessPreference,
            countryCode = countryCode?.let { validatedCountry(it) } ?: this.countryCode,
            profileImageUrl = profileImageUrl?.let { validatedImagePath(it) } ?: this.profileImageUrl,
        )

    companion object {
        private const val PROFILE_IMAGE_PATH_MAX_LENGTH: Int = 512

        private val CATALOG_CODES: Set<String> = IngredientCode.entries.map { it.name }.toSet()

        // hydration 전용 — 검증은 updatedWith 경유가 유일 경로(무검증 저장 차단)
        internal fun of(
            nickname: String?,
            avoidanceSubstanceCodes: Set<AvoidedIngredientCodeRef>,
            spicinessPreference: SpicinessPreference,
            countryCode: CountryCode?,
            profileImageUrl: String? = null,
        ): MemberProfile =
            MemberProfile(
                nickname = nickname,
                avoidanceSubstanceCodes = avoidanceSubstanceCodes,
                spicinessPreference = spicinessPreference,
                countryCode = countryCode,
                profileImageUrl = profileImageUrl,
            )

        fun empty(): MemberProfile =
            MemberProfile(
                nickname = null,
                avoidanceSubstanceCodes = emptySet(),
                spicinessPreference = SpicinessPreference.SKIP,
                countryCode = null,
                profileImageUrl = null,
            )

        private fun validatedNickname(raw: String): String =
            raw.trim().ifBlank { throw BusinessException(ErrorCode.INVALID_NICKNAME) }

        private fun validatedCodes(raw: List<String>): Set<AvoidedIngredientCodeRef> {
            if (raw.any { it !in CATALOG_CODES }) {
                throw BusinessException(ErrorCode.INVALID_AVOIDANCE_SUBSTANCE_CODE)
            }
            return raw.map { AvoidedIngredientCodeRef(it) }.toSet()
        }

        private fun validatedCountry(raw: String): CountryCode =
            CountryCode.from(raw) ?: throw BusinessException(ErrorCode.INVALID_COUNTRY_CODE)

        private fun validatedSpiciness(raw: String): SpicinessPreference =
            SpicinessPreference.from(raw)

        // 저장은 CDN 도메인 없는 경로만 — 빈 문자열·전체 URL 은 거부한다(제거 센티널 없음, 미설정=기본 이미지 경로).
        // 선행 '/' 는 저장 전 제거 — 스토리지 키 컨벤션(무슬래시)으로 통일.
        private fun validatedImagePath(raw: String): String {
            val trimmed = raw.trim().trimStart('/')
            if (trimmed.isEmpty() || trimmed.length > PROFILE_IMAGE_PATH_MAX_LENGTH || ImageUrls.isAbsoluteUrl(trimmed)) {
                throw BusinessException(ErrorCode.INVALID_PROFILE_IMAGE_URL)
            }
            return trimmed
        }
    }
}
