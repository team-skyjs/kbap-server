package com.meogo.application.client.member

import com.meogo.application.client.member.dto.MemberProfileInput
import com.meogo.application.client.member.dto.MemberRankingResult
import com.meogo.application.client.member.dto.MyProfileResult
import com.meogo.application.client.member.dto.ProfileUpdateInput
import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.kernel.lang.CountryCode
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.member.AvoidanceSubstanceCodeRef
import com.meogo.core.member.Member
import com.meogo.core.member.MemberErrorCode
import com.meogo.core.member.MemberException
import com.meogo.core.member.MemberProfile
import com.meogo.core.member.MemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MemberProfileUseCase(
    private val memberRepository: MemberRepository,
) {
    @Transactional
    fun completeOnboarding(input: MemberProfileInput) {
        val member = findMember(input.memberId)

        val profile = MemberProfile.of(
            nickname = validatedNickname(input.nickname),
            avoidanceSubstanceCodes = validatedCodes(input.avoidanceSubstanceCodes),
            spicinessPreference = member.profile.spicinessPreference,
            countryCode = validatedCountry(input.countryCode),
            appLanguage = validatedLanguage(input.appLanguage),
        )

        memberRepository.update(member.updateProfile(profile).completeOnboarding())
    }

    @Transactional
    fun update(input: ProfileUpdateInput) {
        val member = findMember(input.memberId)
        val current = member.profile

        val merged = MemberProfile.of(
            nickname = input.nickname?.let { validatedNickname(it) } ?: current.nickname,
            avoidanceSubstanceCodes = input.avoidanceSubstanceCodes?.let { validatedCodes(it) }
                ?: current.avoidanceSubstanceCodes,
            spicinessPreference = current.spicinessPreference,
            countryCode = input.countryCode?.let { validatedCountry(it) } ?: current.countryCode,
            appLanguage = input.appLanguage?.let { validatedLanguage(it) } ?: current.appLanguage,
        )

        memberRepository.update(member.updateProfile(merged))
    }

    @Transactional(readOnly = true)
    fun getMyProfile(memberId: Long): MyProfileResult {
        val member = findMember(memberId)
        return MyProfileResult.of(member, MemberRankingResult.from(member.ranking))
    }

    private fun findMember(memberId: Long): Member =
        memberRepository.findById(memberId)
            ?: throw MemberException(MemberErrorCode.MEMBER_NOT_FOUND)

    private fun validatedNickname(raw: String): String =
        raw.trim().ifBlank { throw OnboardingException(OnboardingErrorCode.INVALID_NICKNAME) }

    private fun validatedCodes(raw: List<String>): Set<AvoidanceSubstanceCodeRef> {
        if (raw.any { it !in CATALOG_CODES }) {
            throw OnboardingException(OnboardingErrorCode.INVALID_AVOIDANCE_SUBSTANCE_CODE)
        }
        return raw.map { AvoidanceSubstanceCodeRef(it) }.toSet()
    }

    private fun validatedCountry(raw: String): CountryCode =
        CountryCode.from(raw) ?: throw OnboardingException(OnboardingErrorCode.INVALID_COUNTRY_CODE)

    private fun validatedLanguage(raw: String): LanguageCode =
        LanguageCode.entries.firstOrNull { it.code == raw }
            ?: throw OnboardingException(OnboardingErrorCode.UNSUPPORTED_APP_LANGUAGE)

    companion object {
        private val CATALOG_CODES: Set<String> = AvoidanceSubstanceCode.entries.map { it.name }.toSet()
    }
}
