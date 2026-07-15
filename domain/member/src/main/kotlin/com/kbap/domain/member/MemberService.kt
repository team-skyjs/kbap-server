package com.kbap.domain.member

import com.kbap.domain.member.model.AvoidanceSubstanceCodeRef
import com.kbap.domain.member.model.Member
import com.kbap.domain.member.model.MemberProfile
import com.kbap.domain.member.model.MemberStatus
import com.kbap.domain.member.model.SocialIdentity
import com.kbap.core.error.ErrorCode
import com.kbap.core.error.BusinessException
import com.kbap.core.lang.CountryCode
import com.kbap.core.lang.LanguageCode
import com.kbap.domain.avoidance.model.AvoidanceSubstanceCode
import com.kbap.domain.member.dto.MemberProfileInput
import com.kbap.domain.member.dto.MemberRankingResult
import com.kbap.domain.member.dto.MyProfileResult
import com.kbap.domain.member.dto.ProfileUpdateInput
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.net.URISyntaxException

@Service
class MemberService internal constructor(
    private val memberRepository: MemberJpaRepository,
    @Value("\${kbap.member.profile-image-allowed-hosts:}") private val profileImageAllowedHosts: List<String>,
) {
    @Transactional
    fun completeOnboarding(input: MemberProfileInput) {
        val member = findActiveOrThrow(input.memberId)

        val profile = MemberProfile.of(
            nickname = validatedNickname(input.nickname),
            avoidanceSubstanceCodes = validatedCodes(input.avoidanceSubstanceCodes),
            spicinessPreference = input.spicinessPreference?.let { validatedSpiciness(it) }
                ?: member.profile.spicinessPreference,
            countryCode = validatedCountry(input.countryCode),
            appLanguage = validatedLanguage(input.appLanguage),
            profileImageUrl = input.profileImageUrl?.let { validatedImageUrl(it) },
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

    // 소셜 계정 삭제(외부 호출)는 AuthApplicationService 가 트랜잭션 밖에서 선행한다.
    @Transactional
    fun withdraw(memberId: Long) {
        findActiveOrThrow(memberId).withdraw()
    }

    @Transactional(readOnly = true)
    fun findActive(memberId: Long): Member? =
        memberRepository.findByIdAndMemberStatus(memberId, MemberStatus.ACTIVE)

    private fun findByIdentity(identity: SocialIdentity): Member? =
        memberRepository.findByProviderAndProviderUidAndMemberStatus(
            identity.provider,
            identity.providerUserId,
            MemberStatus.ACTIVE,
        )

    // 소셜 신원으로 기존 회원을 찾고, 없으면 가입시킨다. (member, 신규 여부)
    // 의도적 무트랜잭션: unique 제약 위반 폴백(가입 경합)이 세션을 무효화하므로 단일 트랜잭션으로
    // 묶을 수 없다 — 각 레포지토리 호출이 자체 트랜잭션으로 돈다.
    fun findOrSignUp(identity: SocialIdentity): Pair<Member, Boolean> {
        findByIdentity(identity)?.let { return it to false }

        return try {
            memberRepository.save(Member.signUp(identity)) to true
        } catch (e: DataIntegrityViolationException) {
            val existing = findByIdentity(identity)
                ?: throw BusinessException(ErrorCode.DUPLICATE_SOCIAL_IDENTITY)
            existing to false
        }
    }

    @Transactional
    fun increaseScanCount(memberId: Long) {
        if (memberRepository.increaseScanCount(memberId) == 0) {
            throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        }
    }

    // 회원이 기피하는 성분 코드 집합 — 게스트(null)·미존재·미등록이면 빈 집합.
    @Transactional(readOnly = true)
    fun getAvoidedCodes(memberId: Long?): Set<AvoidanceSubstanceCode> {
        if (memberId == null) return emptySet()
        val member = findActive(memberId) ?: return emptySet()
        return member.profile.avoidanceSubstanceCodes
            .mapNotNull { ref -> AvoidanceSubstanceCode.entries.firstOrNull { it.name == ref.value } }
            .toSet()
    }

    private fun findActiveOrThrow(memberId: Long): Member =
        findActive(memberId) ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)

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
        if (raw !in MemberProfile.SPICINESS_RANGE) {
            throw BusinessException(ErrorCode.INVALID_SPICINESS_PREFERENCE)
        }
        return raw
    }

    // 빈 문자열은 "미설정/제거"(null) — 부분 수정의 미전송(null=유지)과 구분되는 센티널.
    private fun validatedImageUrl(raw: String): String? {
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
        if (profileImageAllowedHosts.isNotEmpty() && host !in profileImageAllowedHosts) {
            throw BusinessException(ErrorCode.INVALID_PROFILE_IMAGE_URL)
        }
        return trimmed
    }

    companion object {
        private val CATALOG_CODES: Set<String> = AvoidanceSubstanceCode.entries.map { it.name }.toSet()

        private const val PROFILE_IMAGE_URL_MAX_LENGTH: Int = 512
    }
}
