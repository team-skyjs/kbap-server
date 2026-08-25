package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.MemberRankingEventJpaRepository
import com.kbap.common.domain.member.model.MemberRankingEvent
import com.kbap.common.domain.member.model.RankingEventType
import com.kbap.common.domain.report.ReportJpaRepository
import com.kbap.common.domain.report.model.ReportTargetType
import com.kbap.common.domain.review.AdminReviewFilter
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.domain.review.ReviewLikeJpaRepository
import com.kbap.common.domain.review.model.Review
import com.kbap.common.util.ImageUrls
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminReviewService(
    private val reviewRepository: ReviewJpaRepository,
    private val reviewLikeRepository: ReviewLikeJpaRepository,
    private val reportRepository: ReportJpaRepository,
    private val memberRepository: MemberJpaRepository,
    private val foodRepository: FoodJpaRepository,
    private val rankingEventRepository: MemberRankingEventJpaRepository,
    private val auditRecorder: AdminAuditRecorder,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    @Transactional(readOnly = true)
    fun getReviewPage(filter: AdminReviewFilter, page: Int, size: Int): AdminReviewPageResponse {
        val rows = reviewRepository.findAdminPage(filter, page, size)
        return AdminReviewPageResponse(
            items = toResponses(rows.rows),
            page = page,
            size = size,
            totalCount = rows.totalCount,
            totalPages = totalPagesOf(rows.totalCount, size),
        )
    }

    @Transactional
    fun deleteReview(adminId: Long, reviewId: Long): AdminReviewDeleteResponse {
        val review = getReview(reviewId)
        review.delete()
        val alreadyAdjusted = rankingEventRepository.existsByReviewIdAndEvent(review.id, RankingEventType.REVIEW_DELETED)
        val rankingAdjusted = !alreadyAdjusted && memberRepository.decreaseReviewCount(review.memberId) > 0
        if (rankingAdjusted) {
            val lastReviewOfFood = reviewRepository.countByMemberIdAndFoodId(review.memberId, review.foodId) == 0L
            if (lastReviewOfFood) memberRepository.decreaseUniqueReviewedFoodCount(review.memberId)
            rankingEventRepository.save(MemberRankingEvent.reviewDeleted(review.memberId, review.id, lastReviewOfFood))
        }
        auditRecorder.record(
            adminId, AdminAuditAction.REVIEW_DELETE, AdminAuditTargetType.REVIEW, review.id,
            mapOf("deleted" to false), mapOf("deleted" to true, "rankingAdjusted" to rankingAdjusted),
        )
        return AdminReviewDeleteResponse(id = review.id, memberId = review.memberId, rankingAdjusted = rankingAdjusted)
    }

    @Transactional
    fun removeImages(adminId: Long, reviewId: Long): AdminReviewResponse {
        val review = getReview(reviewId)
        val before = review.imageRefs
        review.removeImages()
        auditRecorder.record(
            adminId, AdminAuditAction.REVIEW_IMAGES_REMOVE, AdminAuditTargetType.REVIEW, review.id,
            mapOf("imageRefs" to before), mapOf("imageRefs" to null),
        )
        return toResponses(listOf(review)).single()
    }

    private fun getReview(reviewId: Long): Review =
        reviewRepository.findById(reviewId).orElseThrow { BusinessException(ErrorCode.REVIEW_NOT_FOUND) }

    private fun toResponses(reviews: List<Review>): List<AdminReviewResponse> {
        if (reviews.isEmpty()) return emptyList()
        val ids = reviews.map { it.id }
        val nicknames = memberRepository.findAllById(reviews.map { it.memberId }.toSet()).associate { it.id to it.nickname }
        val foodNames = foodRepository.findAllById(reviews.map { it.foodId }.toSet()).associate { it.id to it.displayName }
        val likeCounts = reviewLikeRepository.countByReviewIds(ids).associate { it.reviewId to it.likeCount }
        val reportCounts = reportRepository.countByTarget(ReportTargetType.REVIEW, ids).associate { it.targetId to it.reportCount }
        return reviews.map {
            AdminReviewResponse(
                id = it.id,
                memberId = it.memberId,
                memberNickname = nicknames[it.memberId],
                foodId = it.foodId,
                foodDisplayName = foodNames[it.foodId],
                rating = it.rating,
                servingSpeedRating = it.servingSpeedRating,
                staffKindnessRating = it.staffKindnessRating,
                content = it.content,
                imageUrls = it.imageRefs.orEmpty().mapNotNull { ref -> ImageUrls.resolve(imagePublicBaseUrl, ref) },
                placeName = it.place?.name,
                authorCountryCode = it.authorCountryCode,
                likeCount = likeCounts[it.id] ?: 0L,
                reportCount = reportCounts[it.id] ?: 0L,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
            )
        }
    }
}
