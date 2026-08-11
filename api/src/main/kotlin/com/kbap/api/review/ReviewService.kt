package com.kbap.api.review

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodService
import com.kbap.common.domain.image.UploadedImageService
import com.kbap.common.domain.image.model.UploadPurpose
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.MemberRankingEventJpaRepository
import com.kbap.common.domain.block.MemberBlockService
import com.kbap.common.domain.member.MemberService
import com.kbap.common.domain.member.model.MemberRankingEvent
import com.kbap.common.domain.member.model.RankingEventType
import com.kbap.api.core.Page
import com.kbap.common.domain.report.ReportJpaRepository
import com.kbap.common.domain.report.model.ReportTargetType
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.domain.review.ReviewLikeJpaRepository
import com.kbap.common.domain.review.model.Review
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReviewService(
    private val reviewRepository: ReviewJpaRepository,
    private val reviewLikeRepository: ReviewLikeJpaRepository,
    private val foodService: FoodService,
    private val foodRepository: FoodJpaRepository,
    private val memberService: MemberService,
    private val uploadedImageService: UploadedImageService,
    private val rankingEventRepository: MemberRankingEventJpaRepository,
    private val memberRepository: MemberJpaRepository,
    private val memberBlockService: MemberBlockService,
    private val reportRepository: ReportJpaRepository,
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

        val firstReviewOfFood = reviewRepository.countByMemberIdAndFoodId(memberId, foodId) == 1L
        if (firstReviewOfFood) {
            memberService.increaseUniqueReviewedFoodCount(memberId)
        }
        rankingEventRepository.save(MemberRankingEvent.reviewCreated(memberId, review.id, firstReviewOfFood))
        return ReviewResponse.from(review, imagePublicBaseUrl, authorOf(memberId), likeCount = 0, likedByMe = false)
    }

    @Transactional
    fun updateReview(
        memberId: Long,
        reviewId: Long,
        rating: Int,
        content: String?,
        imagePaths: List<String>?,
    ): ReviewResponse {
        val review = getMyReview(memberId, reviewId)
        verifyImageOwnership(memberId, imagePaths)
        review.update(rating = rating, content = content, imageRefs = imagePaths)
        val likeCount = reviewLikeRepository.countByReviewIds(listOf(review.id)).singleOrNull()?.likeCount ?: 0
        val likedByMe = reviewLikeRepository.findLikedReviewIds(memberId, listOf(review.id)).isNotEmpty()
        return ReviewResponse.from(review, imagePublicBaseUrl, authorOf(memberId), likeCount, likedByMe)
    }

    @Transactional
    fun deleteReview(memberId: Long, reviewId: Long) {
        val review = getMyReview(memberId, reviewId)
        if (rankingEventRepository.existsByReviewIdAndEvent(review.id, RankingEventType.REVIEW_DELETED)) {
            throw BusinessException(ErrorCode.REVIEW_NOT_FOUND)
        }
        review.delete()
        memberService.decreaseReviewCount(memberId)
        val lastReviewOfFood = reviewRepository.countByMemberIdAndFoodId(memberId, review.foodId) == 0L
        if (lastReviewOfFood) {
            memberService.decreaseUniqueReviewedFoodCount(memberId)
        }
        rankingEventRepository.save(MemberRankingEvent.reviewDeleted(memberId, review.id, lastReviewOfFood))
    }

    @Transactional
    fun likeReview(memberId: Long, reviewId: Long) {
        if (!reviewRepository.existsById(reviewId)) {
            throw BusinessException(ErrorCode.REVIEW_NOT_FOUND)
        }
        reviewLikeRepository.upsertActive(reviewId = reviewId, memberId = memberId)
    }

    @Transactional
    fun unlikeReview(memberId: Long, reviewId: Long) {
        reviewLikeRepository.findByReviewIdAndMemberId(reviewId, memberId)?.delete()
    }

    @Transactional(readOnly = true)
    fun getReviewPage(
        viewerMemberId: Long,
        foodId: Long?,
        countryCode: String?,
        lang: LanguageCode,
        cursor: Long?,
    ): Page<ReviewResponse> {
        foodId?.let { foodService.getReadyFood(it) }
        return toPage(
            reviewRepository.findReviewPage(
                foodId,
                countryCode,
                cursor,
                excludedMemberIds(viewerMemberId),
                excludedReviewIds(viewerMemberId),
                PageRequest.of(0, PAGE_SIZE + 1),
            ),
            viewerMemberId,
            lang,
        )
    }

    private fun excludedMemberIds(viewerMemberId: Long): List<Long> =
        memberBlockService.getBlockedMemberIds(viewerMemberId).ifEmpty { listOf(-1L) }

    private fun excludedReviewIds(viewerMemberId: Long): List<Long> =
        reportRepository
            .findTargetIdsByReporterMemberIdAndTargetType(viewerMemberId, ReportTargetType.REVIEW)
            .ifEmpty { listOf(-1L) }

    @Transactional(readOnly = true)
    fun getMyReviewPage(memberId: Long, lang: LanguageCode, cursor: Long?): Page<ReviewResponse> =
        toPage(reviewRepository.findMemberReviewPage(memberId, cursor, PageRequest.of(0, PAGE_SIZE + 1)), memberId, lang)

    @Transactional(readOnly = true)
    fun getFoodRatings(foodIds: List<Long>): Map<Long, FoodRating> {
        val distinctIds = foodIds.distinct()
        if (distinctIds.isEmpty()) return emptyMap()
        return reviewRepository.aggregateRatingsByFoodIds(distinctIds).associate {
            it.foodId to FoodRating(
                averageRating = (it.average ?: 0.0).roundToFirstDecimal(),
                reviewCount = it.reviewCount,
            )
        }
    }

    @Transactional(readOnly = true)
    fun getFoodRatingSummary(foodId: Long, viewerCountryCode: String?): RatingSummary {
        val overall = reviewRepository.aggregateRating(foodId, null)
        val sameCountry = viewerCountryCode?.let { reviewRepository.aggregateRating(foodId, it) }
        return RatingSummary(
            averageRating = overall.average?.roundToFirstDecimal(),
            reviewCount = overall.reviewCount,
            sameCountryAverageRating = sameCountry?.average?.roundToFirstDecimal(),
            sameCountryReviewCount = sameCountry?.reviewCount ?: 0,
        )
    }

    private fun Double.roundToFirstDecimal(): Double = Math.round(this * 10) / 10.0

    private fun toPage(rows: List<Review>, viewerMemberId: Long, lang: LanguageCode): Page<ReviewResponse> {
        if (rows.isEmpty()) {
            return Page(items = emptyList(), hasNext = false, nextCursor = null)
        }
        val hasNext = rows.size > PAGE_SIZE
        val page = rows.take(PAGE_SIZE)
        val authorsByMemberId = memberRepository.findAllByIdIncludingWithdrawn(page.map { it.memberId }.toSet())
            .associate { it.id to ReviewAuthorResponse.from(it) }
        val foodsByFoodId = foodRepository.findAllById(page.map { it.foodId }.toSet())
            .associate { it.id to ReviewFoodResponse.from(it, lang, imagePublicBaseUrl) }
        val reviewIds = page.map { it.id }
        val likeCountsByReviewId = reviewLikeRepository.countByReviewIds(reviewIds)
            .associate { it.reviewId to it.likeCount }
        val likedReviewIds = reviewLikeRepository.findLikedReviewIds(viewerMemberId, reviewIds).toSet()
        return Page(
            items = page.map {
                ReviewResponse.from(
                    it,
                    imagePublicBaseUrl,
                    authorsByMemberId[it.memberId],
                    likeCount = likeCountsByReviewId[it.id] ?: 0,
                    likedByMe = it.id in likedReviewIds,
                    food = foodsByFoodId[it.foodId],
                )
            },
            hasNext = hasNext,
            nextCursor = if (hasNext) page.last().id else null,
        )
    }

    private fun authorOf(memberId: Long): ReviewAuthorResponse =
        ReviewAuthorResponse.from(memberService.getMember(memberId))

    private fun getMyReview(memberId: Long, reviewId: Long): Review {
        val review = reviewRepository.findById(reviewId)
            .orElseThrow { BusinessException(ErrorCode.REVIEW_NOT_FOUND) }
        if (!review.isOwnedBy(memberId)) {
            throw BusinessException(ErrorCode.REVIEW_FORBIDDEN)
        }
        return review
    }

    private fun verifyImageOwnership(memberId: Long, imagePaths: List<String>?) {
        if (!uploadedImageService.ownsAllImages(memberId, imagePaths, UploadPurpose.REVIEW)) {
            throw BusinessException(ErrorCode.REVIEW_IMAGE_NOT_VERIFIED)
        }
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}

data class FoodRating(
    val averageRating: Double,
    val reviewCount: Long,
)
