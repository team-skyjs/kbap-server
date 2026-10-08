package com.kbap.api.member

import com.kbap.api.image.UploadedImageService
import com.kbap.common.domain.image.model.UploadPurpose
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.MemberSurveyJpaRepository
import com.kbap.common.domain.order.OrderJpaRepository
import com.kbap.common.domain.member.model.ProfileImagePaths
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberSurvey
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.OnboardingProfileDefaults
import com.kbap.common.domain.member.model.SocialIdentity
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.error.BusinessException
import com.kbap.common.util.ImageUrls
import com.kbap.common.domain.ingredient.model.Avoidance
import com.kbap.common.domain.ingredient.model.IngredientCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class MemberService(
    private val memberRepository: MemberJpaRepository,
    private val uploadedImageService: UploadedImageService,
    private val orderRepository: OrderJpaRepository,
    private val memberSurveyRepository: MemberSurveyJpaRepository,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
    @Value("\${kbap.storage.key-prefix:}") private val storageKeyPrefix: String,
) {
    @Transactional
    fun completeOnboarding(input: MemberProfileInput) {
        val member = getMember(input.memberId)
        verifyProfileUploadCompleted(member, input.profileImageUrl)
        member.completeOnboarding(
            nickname = input.nickname ?: OnboardingProfileDefaults.randomNickname(),
            avoidanceSubstanceCodes = input.avoidanceSubstanceCodes,
            dietCategories = input.dietCategories,
            spicinessPreference = input.spicinessPreference,
            countryCode = input.countryCode,
            profileImageUrl = input.profileImageUrl ?: OnboardingProfileDefaults.randomProfileImagePath(),
            profileImageKeyPrefix = storageKeyPrefix,
        )
    }

    @Transactional
    fun updateProfile(input: ProfileUpdateInput) {
        val member = getMember(input.memberId)
        verifyProfileUploadCompleted(member, input.profileImageUrl)
        member.updateProfile(
            nickname = input.nickname,
            avoidanceSubstanceCodes = input.avoidanceSubstanceCodes,
            dietCategories = input.dietCategories,
            spicinessPreference = input.spicinessPreference,
            countryCode = input.countryCode,
            profileImageUrl = input.profileImageUrl,
            currency = input.currency,
            profileImageKeyPrefix = storageKeyPrefix,
        )
    }

    private fun verifyProfileUploadCompleted(member: Member, requested: String?) {
        val path = member.changedProfileImageOrNull(requested) ?: return
        if (ProfileImagePaths.isDefault(path)) return
        if (!uploadedImageService.ownsAllImages(member.id, listOf(path), UploadPurpose.PROFILE_IMAGE)) {
            throw BusinessException(ErrorCode.INVALID_PROFILE_IMAGE_URL)
        }
    }

    @Transactional(readOnly = true)
    fun getMyProfile(memberId: Long): MyProfileResult {
        val member = getMember(memberId)
        return MyProfileResult.of(
            member = member,
            ranking = MemberRankingResult.from(member.ranking),
            profileImageUrl = ImageUrls.resolve(imagePublicBaseUrl, member.profile.profileImageUrl),
            surveyCompleted = memberSurveyRepository.existsByMemberIdAndSurveyVersion(member.id, MemberSurvey.CURRENT_VERSION),
        )
    }

    @Transactional(readOnly = true)
    fun getRanking(memberId: Long): MemberRankingResult =
        MemberRankingResult.from(getMember(memberId).ranking)

    @Transactional
    fun withdraw(memberId: Long) {
        val member = memberRepository.findByIdForUpdate(memberId)?.takeIf { it.memberStatus == MemberStatus.ACTIVE }
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        member.withdraw()
        orderRepository.eraseLocationByMemberId(memberId)
        memberSurveyRepository.purgeByMemberId(memberId)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun getMemberForShare(memberId: Long): Member =
        memberRepository.findByIdForShare(memberId)?.takeIf { it.memberStatus == MemberStatus.ACTIVE }
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)

    @Transactional(readOnly = true)
    fun getMemberOrNull(memberId: Long): Member? =
        memberRepository.findByIdOrNull(memberId)?.takeIf { it.memberStatus == MemberStatus.ACTIVE }

    private fun findByIdentity(identity: SocialIdentity): Member? =
        memberRepository.findByProviderAndProviderUidAndMemberStatus(
            identity.provider,
            identity.providerUserId,
            MemberStatus.ACTIVE,
        )

    fun findOrSignUp(identity: SocialIdentity): Pair<Member, Boolean> {
        findByIdentity(identity)?.let { return it to false }
        rejectIfSuspended(identity)

        return try {
            memberRepository.save(Member.signUp(identity)) to true
        } catch (e: DataIntegrityViolationException) {
            findByIdentity(identity)?.let { return it to false }
            rejectIfSuspended(identity)
            throw BusinessException(ErrorCode.DUPLICATE_SOCIAL_IDENTITY)
        }
    }

    private fun rejectIfSuspended(identity: SocialIdentity) {
        if (memberRepository.existsByProviderAndProviderUidAndMemberStatus(identity.provider, identity.providerUserId, MemberStatus.SUSPENDED)) {
            throw BusinessException(ErrorCode.MEMBER_SUSPENDED_LOGIN)
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
    fun getAvoidance(memberId: Long?): Avoidance {
        if (memberId == null) return Avoidance.NONE
        return getMemberOrNull(memberId)?.profile?.avoidance() ?: Avoidance.NONE
    }


    @Transactional(readOnly = true)
    fun getMember(memberId: Long): Member =
        getMemberOrNull(memberId) ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
}
