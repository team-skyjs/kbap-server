package com.kbap.api.review

import com.kbap.api.image.UploadPurpose
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodService
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.member.MemberService
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.domain.review.model.Review
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReviewService(
    private val reviewRepository: ReviewJpaRepository,
    private val foodService: FoodService,
    private val memberService: MemberService,
    private val uploadedImageRepository: UploadedImageJpaRepository,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    @Transactional
    fun createReview(
        memberId: Long,
        foodId: Long,
        rating: Int,
        content: String?,
        imagePaths: List<String>?,
    ): ReviewResponse {
        foodService.getReadyFood(foodId)
        verifyImageOwnership(memberId, imagePaths)
        val authorCountryCode = memberService.getMember(memberId).profile.countryCode?.name
        memberService.increaseReviewCount(memberId)

        val review = reviewRepository.save(
            Review(
                memberId = memberId,
                foodId = foodId,
                rating = rating,
                content = content,
                imageRefs = imagePaths,
                authorCountryCode = authorCountryCode,
            ),
        )

        if (reviewRepository.countByMemberIdAndFoodId(memberId, foodId) == 1L) {
            memberService.increaseUniqueReviewedFoodCount(memberId)
        }
        return ReviewResponse.from(review, imagePublicBaseUrl)
    }

    @Transactional
    fun updateReview(
        memberId: Long,
        reviewId: Long,
        rating: Int,
        content: String?,
        imagePaths: List<String>?,
    ): ReviewResponse {
        val review = getOwnedReview(memberId, reviewId)
        verifyImageOwnership(memberId, imagePaths)
        review.update(rating = rating, content = content, imageRefs = imagePaths)
        return ReviewResponse.from(review, imagePublicBaseUrl)
    }

    @Transactional
    fun deleteReview(memberId: Long, reviewId: Long) {
        val review = getOwnedReview(memberId, reviewId)
        review.delete()
        memberService.decreaseReviewCount(memberId)
        if (reviewRepository.countByMemberIdAndFoodId(memberId, review.foodId) == 0L) {
            memberService.decreaseUniqueReviewedFoodCount(memberId)
        }
    }

    private fun getOwnedReview(memberId: Long, reviewId: Long): Review {
        val review = reviewRepository.findByIdForUpdate(reviewId)
            ?: throw BusinessException(ErrorCode.REVIEW_NOT_FOUND)
        if (!review.isOwnedBy(memberId)) {
            throw BusinessException(ErrorCode.REVIEW_FORBIDDEN)
        }
        return review
    }

    private fun verifyImageOwnership(memberId: Long, imagePaths: List<String>?) {
        if (imagePaths.isNullOrEmpty()) return
        val ownedReviewPaths = uploadedImageRepository.findByPathIn(imagePaths)
            .filter { it.isOwnedBy(memberId) && it.path.contains(REVIEW_IMAGE_PATH_SEGMENT) }
            .map { it.path }
            .toSet()
        if (!ownedReviewPaths.containsAll(imagePaths)) {
            throw BusinessException(ErrorCode.REVIEW_IMAGE_NOT_VERIFIED)
        }
    }

    companion object {
        val REVIEW_IMAGE_PATH_SEGMENT = "images/${UploadPurpose.REVIEW.prefix}/"
    }
}
