package com.kbap.domain.member

import com.kbap.domain.member.model.Member
import com.kbap.domain.member.model.MemberStatus
import com.kbap.domain.member.model.SocialIdentity
import com.kbap.core.error.ErrorCode
import com.kbap.core.error.BusinessException
import com.kbap.core.image.ImageUrls
import com.kbap.domain.avoidance.model.AvoidanceSubstanceCode
import com.kbap.domain.member.dto.MemberProfileInput
import com.kbap.domain.member.dto.MemberRankingResult
import com.kbap.domain.member.dto.MyProfileResult
import com.kbap.domain.member.dto.ProfileUpdateInput
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MemberService(
    private val memberRepository: MemberJpaRepository,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    @Transactional
    fun completeOnboarding(input: MemberProfileInput) {
        getMember(input.memberId).completeOnboarding(
            nickname = input.nickname,
            avoidanceSubstanceCodes = input.avoidanceSubstanceCodes,
            spicinessPreference = input.spicinessPreference,
            countryCode = input.countryCode,
            profileImageUrl = input.profileImageUrl,
        )
    }

    @Transactional
    fun updateProfile(input: ProfileUpdateInput) {
        getMember(input.memberId).updateProfile(
            nickname = input.nickname,
            avoidanceSubstanceCodes = input.avoidanceSubstanceCodes,
            spicinessPreference = input.spicinessPreference,
            countryCode = input.countryCode,
            profileImageUrl = input.profileImageUrl,
        )
    }

    @Transactional(readOnly = true)
    fun getMyProfile(memberId: Long): MyProfileResult {
        val member = getMember(memberId)
        return MyProfileResult.of(
            member = member,
            ranking = MemberRankingResult.from(member.ranking),
            profileImageUrl = ImageUrls.resolve(imagePublicBaseUrl, member.profile.profileImageUrl),
        )
    }

    @Transactional(readOnly = true)
    fun getRanking(memberId: Long): MemberRankingResult =
        MemberRankingResult.from(getMember(memberId).ranking)

    // 소셜 계정 삭제(외부 호출)는 AuthApplicationService 가 트랜잭션 밖에서 선행한다.
    @Transactional
    fun withdraw(memberId: Long) {
        getMember(memberId).withdraw()
    }

    @Transactional(readOnly = true)
    fun getMemberOrNull(memberId: Long): Member? =
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
        return getMemberOrNull(memberId)?.profile?.avoidedCodes() ?: emptySet()
    }

    @Transactional(readOnly = true)
    fun getMember(memberId: Long): Member =
        getMemberOrNull(memberId) ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
}
