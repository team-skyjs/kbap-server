package com.kbap.api.review

import com.kbap.api.image.UploadPurpose
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodService
import com.kbap.common.domain.image.UploadedImageJpaRepository
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
    private val memberService: MemberService,
    private val uploadedImageRepository: UploadedImageJpaRepository,
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
    fun getFoodReviewPage(viewerMemberId: Long, foodId: Long, countryCode: String?, cursor: Long?): Page<ReviewResponse> {
        foodService.getReadyFood(foodId)
        // 빈 목록의 NOT IN 은 방언별 렌더링이 갈려 -1 센티널로 통일(id 는 IDENTITY ≥ 1)
        val excludedMemberIds = memberBlockService.getBlockedMemberIds(viewerMemberId).ifEmpty { listOf(-1L) }
        val excludedReviewIds = reportRepository
            .findTargetIdsByReporterMemberIdAndTargetType(viewerMemberId, ReportTargetType.REVIEW)
            .ifEmpty { listOf(-1L) }
        return toPage(
            reviewRepository.findFoodReviewPage(
                foodId,
                countryCode,
                cursor,
                excludedMemberIds,
                excludedReviewIds,
                PageRequest.of(0, PAGE_SIZE + 1),
            ),
            viewerMemberId,
        )
    }

    @Transactional(readOnly = true)
    fun getMyReviewPage(memberId: Long, cursor: Long?): Page<ReviewResponse> =
        toPage(reviewRepository.findMemberReviewPage(memberId, cursor, PageRequest.of(0, PAGE_SIZE + 1)), memberId)

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

    private fun toPage(rows: List<Review>, viewerMemberId: Long): Page<ReviewResponse> {
        val hasNext = rows.size > PAGE_SIZE
        val page = rows.take(PAGE_SIZE)
        val authorsByMemberId = memberRepository.findAllById(page.map { it.memberId }.toSet())
            .associate { it.id to ReviewAuthorResponse.from(it) }
        val reviewIds = page.map { it.id }
        val likeCountsByReviewId = if (reviewIds.isEmpty()) {
            emptyMap()
        } else {
            reviewLikeRepository.countByReviewIds(reviewIds).associate { it.reviewId to it.likeCount }
        }
        val likedReviewIds = if (reviewIds.isEmpty()) {
            emptySet()
        } else {
            reviewLikeRepository.findLikedReviewIds(viewerMemberId, reviewIds).toSet()
        }
        return Page(
            items = page.map {
                ReviewResponse.from(
                    it,
                    imagePublicBaseUrl,
                    authorsByMemberId[it.memberId],
                    likeCount = likeCountsByReviewId[it.id] ?: 0,
                    likedByMe = it.id in likedReviewIds,
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
