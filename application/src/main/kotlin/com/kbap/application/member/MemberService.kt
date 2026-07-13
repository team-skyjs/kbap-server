package com.kbap.application.member

import com.kbap.application.auth.social.SocialAccountDeleter
import com.kbap.application.member.dto.MemberProfileInput
import com.kbap.application.member.dto.MemberRankingResult
import com.kbap.application.member.dto.MyProfileResult
import com.kbap.application.member.dto.ProfileUpdateInput
import com.kbap.core.error.ErrorCode
import com.kbap.core.error.KbapException
import com.kbap.core.lang.CountryCode
import com.kbap.core.lang.LanguageCode
import com.kbap.domain.avoidance.AvoidanceSubstanceCode
import com.kbap.domain.member.AvoidanceSubstanceCodeRef
import com.kbap.domain.member.Member
import com.kbap.domain.member.MemberJpaRepository
import com.kbap.domain.member.MemberProfile
import com.kbap.domain.member.MemberStatus
import com.kbap.domain.member.SocialIdentity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MemberService(
    private val memberRepository: MemberJpaRepository,
    private val socialAccountDeleter: SocialAccountDeleter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

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

        member.updateProfile(profile)
        member.completeOnboarding()
    }

    @Transactional
    fun updateProfile(input: ProfileUpdateInput) {
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

        member.updateProfile(merged)
    }

    @Transactional(readOnly = true)
    fun getMyProfile(memberId: Long): MyProfileResult {
        val member = findMember(memberId)
        return MyProfileResult.of(member, MemberRankingResult.from(member.ranking))
    }

    @Transactional(readOnly = true)
    fun getRanking(memberId: Long): MemberRankingResult =
        MemberRankingResult.from(findMember(memberId).ranking)

    fun withdraw(memberId: Long) {
        val member = findMember(memberId)

        deleteSocialAccount(memberId, member.identity)

        member.withdraw()
        memberRepository.save(member)
    }

    private fun findMember(memberId: Long): Member =
        memberRepository.findByIdAndMemberStatus(memberId, MemberStatus.ACTIVE)
            ?: throw KbapException(ErrorCode.MEMBER_NOT_FOUND)

    private fun deleteSocialAccount(memberId: Long, identity: SocialIdentity) {
        try {
            socialAccountDeleter.delete(identity.provider, identity.providerUserId)
        } catch (e: Exception) {
            log.error(
                "소셜 계정 삭제 실패 — 관리자가 콘솔에서 직접 삭제해야 한다: " +
                    "memberId={}, provider={}, providerUserId={}",
                memberId,
                identity.provider,
                identity.providerUserId,
                e,
            )
            throw KbapException(ErrorCode.SOCIAL_ACCOUNT_DELETE_FAILED)
        }
    }

    private fun validatedNickname(raw: String): String =
        raw.trim().ifBlank { throw KbapException(ErrorCode.INVALID_NICKNAME) }

    private fun validatedCodes(raw: List<String>): Set<AvoidanceSubstanceCodeRef> {
        if (raw.any { it !in CATALOG_CODES }) {
            throw KbapException(ErrorCode.INVALID_AVOIDANCE_SUBSTANCE_CODE)
        }
        return raw.map { AvoidanceSubstanceCodeRef(it) }.toSet()
    }

    private fun validatedCountry(raw: String): CountryCode =
        CountryCode.from(raw) ?: throw KbapException(ErrorCode.INVALID_COUNTRY_CODE)

    private fun validatedLanguage(raw: String): LanguageCode =
        LanguageCode.entries.firstOrNull { it.code == raw }
            ?: throw KbapException(ErrorCode.UNSUPPORTED_APP_LANGUAGE)

    companion object {
        private val CATALOG_CODES: Set<String> = AvoidanceSubstanceCode.entries.map { it.name }.toSet()
    }
}
