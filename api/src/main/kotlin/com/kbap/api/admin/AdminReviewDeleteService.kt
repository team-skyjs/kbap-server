package com.kbap.api.admin

import com.kbap.api.review.ReviewService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.review.ReviewJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminReviewDeleteService(
    private val reviews: ReviewJpaRepository,
    private val reviewService: ReviewService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun delete(adminId: Long, reviewId: Long) {
        val review = reviews.findByIdForUpdate(reviewId)
            ?: throw BusinessException(ErrorCode.REVIEW_NOT_FOUND)
        reviewService.deleteForModeration(review)
        log.info("관리자 리뷰 삭제 adminId={} reviewId={}", adminId, reviewId)
    }
}
