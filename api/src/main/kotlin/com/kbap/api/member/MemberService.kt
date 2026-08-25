package com.kbap.api.member

import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.OnboardingProfileDefaults
import com.kbap.common.domain.member.model.SocialIdentity
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.error.BusinessException
import com.kbap.common.util.ImageUrls
import com.kbap.common.domain.ingredient.model.IngredientCode
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
            nickname = input.nickname ?: OnboardingProfileDefaults.randomNickname(),
            avoidanceSubstanceCodes = input.avoidanceSubstanceCodes,
            dietCategories = input.dietCategories,
            spicinessPreference = input.spicinessPreference,
            countryCode = input.countryCode,
            profileImageUrl = input.profileImageUrl ?: OnboardingProfileDefaults.randomProfileImagePath(),
        )
    }

    @Transactional
    fun updateProfile(input: ProfileUpdateInput) {
        getMember(input.memberId).updateProfile(
            nickname = input.nickname,
            avoidanceSubstanceCodes = input.avoidanceSubstanceCodes,
            dietCategories = input.dietCategories,
            spicinessPreference = input.spicinessPreference,
            countryCode = input.countryCode,
            profileImageUrl = input.profileImageUrl,
            currency = input.currency,
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

    @Transactional
    fun withdraw(memberId: Long) {
        getMember(memberId).withdraw()
    }

    @Transactional(readOnly = true)
    fun getMemberOrNull(memberId: Long): Member? =
        memberRepository.findByIdAndMemberStatus(memberId, MemberStatus.ACTIVE)

    private fun findByIdentity(identity: SocialIdentity): Member? {
        val member = memberRepository.findByProviderAndProviderUid(identity.provider, identity.providerUserId) ?: return null
        if (member.isSuspended()) throw BusinessException(ErrorCode.MEMBER_SUSPENDED)
        return member
    }

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

    @Transactional
    fun increaseReviewCount(memberId: Long) {
        if (memberRepository.increaseReviewCount(memberId) == 0) {
            throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        }
    }

    @Transactional
    fun decreaseReviewCount(memberId: Long) {
        if (memberRepository.decreaseReviewCount(memberId) == 0) {
            throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        }
    }

    @Transactional
    fun increaseUniqueReviewedFoodCount(memberId: Long) {
        if (memberRepository.increaseUniqueReviewedFoodCount(memberId) == 0) {
            throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        }
    }

    @Transactional
    fun decreaseUniqueReviewedFoodCount(memberId: Long) {
        if (memberRepository.decreaseUniqueReviewedFoodCount(memberId) == 0) {
            throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        }
    }

    @Transactional(readOnly = true)
    fun getAvoidedCodes(memberId: Long?): Set<IngredientCode> {
        if (memberId == null) return emptySet()
        return getMemberOrNull(memberId)?.profile?.avoidedCodes() ?: emptySet()
    }

    @Transactional(readOnly = true)
    fun getMember(memberId: Long): Member {
        val member = memberRepository.findById(memberId).orElse(null) ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        if (member.isSuspended()) throw BusinessException(ErrorCode.MEMBER_SUSPENDED)
        return member
    }
}
