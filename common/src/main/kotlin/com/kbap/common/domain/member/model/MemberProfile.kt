package com.kbap.common.domain.member.model

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.util.ImageUrls
import com.kbap.common.domain.CurrencyCode
import com.kbap.common.domain.ingredient.model.DietCategory
import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.domain.member.model.CountryCode

@ConsistentCopyVisibility
data class MemberProfile private constructor(
    val nickname: String?,
    val avoidanceSubstanceCodes: Set<AvoidedIngredientCodeRef>,
    val dietCategories: Set<DietCategory>,
    val spicinessPreference: SpicinessPreference,
    val countryCode: CountryCode?,
    val profileImageUrl: String?,
    val currency: CurrencyCode?,
) {
    fun avoidedCodes(): Set<IngredientCode> =
        avoidanceSubstanceCodes
            .mapNotNull { ref -> IngredientCode.entries.firstOrNull { it.name == ref.value } }
            .toSet()

    fun updatedWith(
        nickname: String? = null,
        avoidanceSubstanceCodes: List<String>? = null,
        dietCategories: List<String>? = null,
        spicinessPreference: String? = null,
        countryCode: String? = null,
        profileImageUrl: String? = null,
        currency: String? = null,
    ): MemberProfile =
        of(
            nickname = nickname?.let { validatedNickname(it) } ?: this.nickname,
            avoidanceSubstanceCodes = avoidanceSubstanceCodes?.let { validatedCodes(it) }
                ?: this.avoidanceSubstanceCodes,
            dietCategories = dietCategories?.let { validatedDiets(it) } ?: this.dietCategories,
            spicinessPreference = spicinessPreference?.let { validatedSpiciness(it) }
                ?: this.spicinessPreference,
            countryCode = countryCode?.let { validatedCountry(it) } ?: this.countryCode,
            profileImageUrl = profileImageUrl?.let { validatedImagePath(it) } ?: this.profileImageUrl,
            currency = currency?.let { validatedCurrency(it) } ?: this.currency,
        )

    companion object {
        private const val PROFILE_IMAGE_PATH_MAX_LENGTH: Int = 512

        private val CATALOG_CODES: Set<String> = IngredientCode.entries.map { it.name }.toSet()

        internal fun of(
            nickname: String?,
            avoidanceSubstanceCodes: Set<AvoidedIngredientCodeRef>,
            spicinessPreference: SpicinessPreference,
            countryCode: CountryCode?,
            profileImageUrl: String? = null,
            currency: CurrencyCode? = null,
            dietCategories: Set<DietCategory> = emptySet(),
        ): MemberProfile =
            MemberProfile(
                nickname = nickname,
                avoidanceSubstanceCodes = avoidanceSubstanceCodes,
                dietCategories = dietCategories,
                spicinessPreference = spicinessPreference,
                countryCode = countryCode,
                profileImageUrl = profileImageUrl,
                currency = currency,
            )

        fun empty(): MemberProfile =
            MemberProfile(
                nickname = null,
                avoidanceSubstanceCodes = emptySet(),
                dietCategories = emptySet(),
                spicinessPreference = SpicinessPreference.SKIP,
                countryCode = null,
                profileImageUrl = null,
                currency = null,
            )

        private fun validatedNickname(raw: String): String =
            raw.trim().ifBlank { throw BusinessException(ErrorCode.INVALID_NICKNAME) }

        private fun validatedCodes(raw: List<String>): Set<AvoidedIngredientCodeRef> {
            if (raw.any { it !in CATALOG_CODES }) {
                throw BusinessException(ErrorCode.INVALID_AVOIDANCE_SUBSTANCE_CODE)
            }
            return raw.map { AvoidedIngredientCodeRef(it) }.toSet()
        }

        private fun validatedDiets(raw: List<String>): Set<DietCategory> =
            raw.map { code ->
                DietCategory.entries.firstOrNull { it.name == code }
                    ?: throw BusinessException(ErrorCode.INVALID_DIET_CATEGORY)
            }.toSet()

        private fun validatedCountry(raw: String): CountryCode =
            CountryCode.from(raw) ?: throw BusinessException(ErrorCode.INVALID_COUNTRY_CODE)

        private fun validatedSpiciness(raw: String): SpicinessPreference =
            SpicinessPreference.from(raw)

        private fun validatedCurrency(raw: String): CurrencyCode =
            CurrencyCode.from(raw) ?: throw BusinessException(ErrorCode.INVALID_CURRENCY_CODE)

        private fun validatedImagePath(raw: String): String {
            val trimmed = raw.trim().trimStart('/')
            if (trimmed.isEmpty() || trimmed.length > PROFILE_IMAGE_PATH_MAX_LENGTH || ImageUrls.isAbsoluteUrl(trimmed)) {
                throw BusinessException(ErrorCode.INVALID_PROFILE_IMAGE_URL)
            }
            return trimmed
        }
    }
}
