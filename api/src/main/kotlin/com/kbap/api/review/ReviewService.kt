package com.kbap.api.review

import com.kbap.api.image.UploadPurpose
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
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class ReviewService(
    private val reviewRepository: ReviewJpaRepository,
    private val foodService: FoodService,
    private val memberService: MemberService,
    private val uploadedImageRepository: UploadedImageJpaRepository,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    // RR 스냅샷이 회원 락 대기 전에 고정되면 첫/마지막 판정 count 가 과거를 본다 — 락 해제 후 최신 커밋을 읽도록 RC 명시
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun createReview(
        memberId: Long,
        foodId: Long,
        rating: Int,
        content: String?,
        imagePaths: List<String>?,
    ): ReviewResponse {
        foodService.getReadyFood(foodId)
        verifyImageOwnership(memberId, imagePaths)
        val authorCountryCode = memberService.getMemberForUpdate(memberId).profile.countryCode?.name

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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun deleteReview(memberId: Long, reviewId: Long) {
        val review = getOwnedReview(memberId, reviewId)
        memberService.getMemberForUpdate(memberId)
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

    @Transactional(readOnly = true)
    fun getFoodRatingSummary(foodId: Long, viewerCountryCode: String?): RatingSummary {
        val overall = reviewRepository.aggregateRating(foodId, null)
        val sameCountry = viewerCountryCode?.let { reviewRepository.aggregateRating(foodId, it) }
        return RatingSummary(
            averageRating = overall.average?.roundToFirstDecimal(),
            reviewCount = overall.reviewCount,
            sameCountryAverageRating = sameCountry?.average?.roundToFirstDecimal(),
        )
    }

    private fun Double.roundToFirstDecimal(): Double = Math.round(this * 10) / 10.0

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
        val ownedReviewPaths = uploadedImageRepository.findByPathIn(imagePaths)
            .filter { it.isOwnedBy(memberId) && it.path.contains(REVIEW_IMAGE_PATH_SEGMENT) }
            .map { it.path }
            .toSet()
        if (!ownedReviewPaths.containsAll(imagePaths)) {
            throw BusinessException(ErrorCode.REVIEW_IMAGE_NOT_VERIFIED)
        }
    }

    companion object {
        const val PAGE_SIZE = 20
        val REVIEW_IMAGE_PATH_SEGMENT = "images/${UploadPurpose.REVIEW.prefix}/"
    }
}
