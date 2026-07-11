package com.meogo.application.client.member

import com.meogo.application.client.member.dto.MyProfileResult
import com.meogo.application.client.member.dto.MemberProfileInput
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
        val member = memberRepository.findById(input.memberId)
            ?: throw MemberException(MemberErrorCode.MEMBER_NOT_FOUND)

        val profile = validatedProfile(input, member)
        val updated = member.updateProfile(profile).completeOnboarding()
        memberRepository.update(updated)
    }

    @Transactional
    fun update(input: MemberProfileInput) {
        val member = memberRepository.findById(input.memberId)
            ?: throw MemberException(MemberErrorCode.MEMBER_NOT_FOUND)

        val profile = validatedProfile(input, member)
        memberRepository.update(member.updateProfile(profile))
    }

    @Transactional(readOnly = true)
    fun getMyProfile(memberId: Long): MyProfileResult {
        val member = memberRepository.findById(memberId)
            ?: throw MemberException(MemberErrorCode.MEMBER_NOT_FOUND)
        return MyProfileResult.from(member)
    }

    private fun validatedProfile(input: MemberProfileInput, member: Member): MemberProfile {
        val nickname = input.nickname.trim()
        if (nickname.isBlank()) {
            throw OnboardingException(OnboardingErrorCode.INVALID_NICKNAME)
        }
        if (input.avoidanceSubstanceCodes.any { it !in CATALOG_CODES }) {
            throw OnboardingException(OnboardingErrorCode.INVALID_AVOIDANCE_SUBSTANCE_CODE)
        }
        val countryCode = CountryCode.from(input.countryCode)
            ?: throw OnboardingException(OnboardingErrorCode.INVALID_COUNTRY_CODE)
        val appLanguage = LanguageCode.entries.firstOrNull { it.code == input.appLanguage }
            ?: throw OnboardingException(OnboardingErrorCode.UNSUPPORTED_APP_LANGUAGE)

        return MemberProfile.of(
            nickname = nickname,
            avoidanceSubstanceCodes = input.avoidanceSubstanceCodes.map { AvoidanceSubstanceCodeRef(it) }.toSet(),
            spicinessPreference = member.profile.spicinessPreference,
            countryCode = countryCode,
            appLanguage = appLanguage,
        )
    }

    companion object {
        private val CATALOG_CODES: Set<String> = AvoidanceSubstanceCode.entries.map { it.name }.toSet()
    }
}
