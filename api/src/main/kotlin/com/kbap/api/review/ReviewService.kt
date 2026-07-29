package com.kbap.api.review

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodService
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.member.MemberService
import com.kbap.api.core.Page
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.domain.review.model.Review
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
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

        val firstReviewOfFood = reviewRepository.countByMemberIdAndFoodId(memberId, foodId) == 1L
        memberService.increaseReviewCounts(memberId, firstReviewOfFood)
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
        val lastReviewOfFood = reviewRepository.countByMemberIdAndFoodId(memberId, review.foodId) == 0L
        memberService.decreaseReviewCounts(memberId, lastReviewOfFood)
    }

    @Transactional(readOnly = true)
    fun getFoodReviewPage(foodId: Long, countryCode: String?, cursor: Long?): Page<ReviewResponse> {
        foodService.getReadyFood(foodId)
        return toPage(reviewRepository.findFoodReviewPage(foodId, countryCode, cursor, PageRequest.of(0, PAGE_SIZE + 1)))
    }

    @Transactional(readOnly = true)
    fun getMyReviewPage(memberId: Long, cursor: Long?): Page<ReviewResponse> =
        toPage(reviewRepository.findMemberReviewPage(memberId, cursor, PageRequest.of(0, PAGE_SIZE + 1)))

    private fun toPage(rows: List<Review>): Page<ReviewResponse> {
        val hasNext = rows.size > PAGE_SIZE
        val page = rows.take(PAGE_SIZE)
        return Page(
            items = page.map { ReviewResponse.from(it, imagePublicBaseUrl) },
            hasNext = hasNext,
            nextCursor = if (hasNext) page.last().id else null,
        )
    }

    private fun getOwnedReview(memberId: Long, reviewId: Long): Review {
        val review = reviewRepository.findById(reviewId)
            .orElseThrow { BusinessException(ErrorCode.REVIEW_NOT_FOUND) }
        if (!review.isOwnedBy(memberId)) {
            throw BusinessException(ErrorCode.REVIEW_FORBIDDEN)
        }
        return review
    }

    private fun verifyImageOwnership(memberId: Long, imagePaths: List<String>?) {
        if (imagePaths.isNullOrEmpty()) return
        val ownedPaths = uploadedImageRepository.findByPathIn(imagePaths)
            .filter { it.isOwnedBy(memberId) }
            .map { it.path }
            .toSet()
        if (!ownedPaths.containsAll(imagePaths)) {
            throw BusinessException(ErrorCode.REVIEW_IMAGE_NOT_VERIFIED)
        }
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}
