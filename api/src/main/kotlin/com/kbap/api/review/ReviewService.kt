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
    // 카운트 UPDATE 가 잡는 회원 행 X-lock 이 첫/마지막 판정을 직렬화한다 — 판정 count 는 반드시 UPDATE 뒤에,
    // UPDATE 는 INSERT 보다 먼저(INSERT 의 FK 검증이 member 행 S-lock 을 선점하면 X-lock 승격 데드락).
    // RC 명시는 락 대기 후 최신 커밋을 읽기 위함(RR 스냅샷은 락 대기 전 시점으로 고정돼 과거를 본다).
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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun deleteReview(memberId: Long, reviewId: Long) {
        val review = getOwnedReview(memberId, reviewId)
        review.delete()
        memberService.decreaseReviewCount(memberId)
        if (reviewRepository.countByMemberIdAndFoodId(memberId, review.foodId) == 0L) {
            memberService.decreaseUniqueReviewedFoodCount(memberId)
        }
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

    // 리뷰 행 X-lock 조회 — 같은 리뷰의 동시 삭제 중복 차감, 수정의 stale ACTIVE 가 삭제를 덮어쓰는 경합 차단
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
        const val PAGE_SIZE = 20
        val REVIEW_IMAGE_PATH_SEGMENT = "images/${UploadPurpose.REVIEW.prefix}/"
    }
}
