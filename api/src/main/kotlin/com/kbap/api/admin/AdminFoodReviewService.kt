package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodReviewField
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminFoodReviewService(
    private val foodRepository: FoodJpaRepository,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    @Transactional(readOnly = true)
    fun getReviewTargets(limit: Int): AdminFoodReviewTargetsResponse {
        require(limit in 1..MAX_REVIEW_TARGETS) { "limit 은 1..$MAX_REVIEW_TARGETS 여야 합니다: $limit" }
        val targets = foodRepository.findByContentStatusOrderByIdAsc(
            FoodContentStatus.PENDING_REVIEW,
            PageRequest.of(0, limit),
        )
        return AdminFoodReviewTargetsResponse.from(targets, imagePublicBaseUrl)
    }

    @Transactional
    fun applyReviewResult(
        foodId: Long,
        passed: Boolean,
        rejectedFields: Set<FoodReviewField>,
        reason: String?,
    ): AdminFoodReviewResultResponse {
        val food = foodRepository.findById(foodId).orElseThrow { BusinessException(ErrorCode.FOOD_NOT_FOUND) }
        if (passed) {
            food.passReview()
        } else {
            food.rejectReview(rejectedFields, reason)
        }
        return AdminFoodReviewResultResponse.from(food)
    }

    companion object {
        const val DEFAULT_REVIEW_TARGETS = 50

        const val MAX_REVIEW_TARGETS = 200
    }
}
