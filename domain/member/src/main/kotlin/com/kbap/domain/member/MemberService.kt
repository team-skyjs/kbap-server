package com.kbap.domain.member

import com.kbap.core.error.ErrorCode
import com.kbap.core.error.KbapException
import com.kbap.core.lang.CountryCode
import com.kbap.core.lang.LanguageCode
import com.kbap.domain.avoidance.AvoidanceSubstanceCode
import com.kbap.domain.member.dto.MemberProfileInput
import com.kbap.domain.member.dto.MemberRankingResult
import com.kbap.domain.member.dto.MyProfileResult
import com.kbap.domain.member.dto.ProfileUpdateInput
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
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
        val member = findActiveOrThrow(input.memberId)

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
        val member = findActiveOrThrow(input.memberId)
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
        val member = findActiveOrThrow(memberId)
        return MyProfileResult.of(member, MemberRankingResult.from(member.ranking))
    }

    @Transactional(readOnly = true)
    fun getRanking(memberId: Long): MemberRankingResult =
        MemberRankingResult.from(findActiveOrThrow(memberId).ranking)

    fun withdraw(memberId: Long) {
        val member = findActiveOrThrow(memberId)

        deleteSocialAccount(memberId, member.identity)

        member.withdraw()
        memberRepository.save(member)
    }

    fun findActive(memberId: Long): Member? =
        memberRepository.findByIdAndMemberStatus(memberId, MemberStatus.ACTIVE)

    private fun findByIdentity(identity: SocialIdentity): Member? =
        memberRepository.findByProviderAndProviderUidAndMemberStatus(
            identity.provider,
            identity.providerUserId,
            MemberStatus.ACTIVE,
        )

    // 소셜 신원으로 기존 회원을 찾고, 없으면 가입시킨다. (member, 신규 여부)
    fun findOrSignUp(identity: SocialIdentity): Pair<Member, Boolean> {
        findByIdentity(identity)?.let { return it to false }

        return try {
            memberRepository.save(Member.signUp(identity)) to true
        } catch (e: DataIntegrityViolationException) {
            val existing = findByIdentity(identity)
                ?: throw KbapException(ErrorCode.DUPLICATE_SOCIAL_IDENTITY)
            existing to false
        }
    }

    @Transactional
    fun increaseScanCount(memberId: Long) {
        if (memberRepository.increaseScanCount(memberId) == 0) {
            throw KbapException(ErrorCode.MEMBER_NOT_FOUND)
        }
    }

    // 회원이 기피하는 성분 코드 집합 — 게스트(null)·미존재·미등록이면 빈 집합.
    fun avoidedCodes(memberId: Long?): Set<AvoidanceSubstanceCode> {
        if (memberId == null) return emptySet()
        val member = findActive(memberId) ?: return emptySet()
        return member.profile.avoidanceSubstanceCodes
            .mapNotNull { ref -> AvoidanceSubstanceCode.entries.firstOrNull { it.name == ref.value } }
            .toSet()
    }

    private fun findActiveOrThrow(memberId: Long): Member =
        findActive(memberId) ?: throw KbapException(ErrorCode.MEMBER_NOT_FOUND)

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
