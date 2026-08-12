package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodVectorOutbox
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminFoodContentReviewService(
    private val foodRepository: FoodJpaRepository,
    private val vectorOutboxRepository: FoodVectorOutboxJpaRepository,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    @Transactional(readOnly = true)
    fun getContentReviewTargets(limit: Int): AdminFoodContentReviewTargetsResponse {
        require(limit in 1..MAX_CONTENT_REVIEW_TARGETS) { "limit 은 1..$MAX_CONTENT_REVIEW_TARGETS 여야 합니다: $limit" }
        val targets = foodRepository.findByContentStatusOrderByIdAsc(
            FoodContentStatus.PENDING_REVIEW,
            PageRequest.of(0, limit),
        )
        return AdminFoodContentReviewTargetsResponse.from(targets, imagePublicBaseUrl)
    }

    @Transactional
    fun applyContentReviewResult(
        foodId: Long,
        passed: Boolean,
        reason: String?,
    ): AdminFoodContentReviewResultResponse {
        val food = foodRepository.findById(foodId).orElseThrow { BusinessException(ErrorCode.FOOD_NOT_FOUND) }
        if (passed) {
            if (food.approve()) {
                enqueueVectorUpsert(food.id)
            }
        } else {
            food.reject(reason)
        }
        return AdminFoodContentReviewResultResponse.from(food)
    }

    private fun enqueueVectorUpsert(foodId: Long) {
        val alreadyPending = vectorOutboxRepository.existsByFoodIdAndOperationAndOutboxStatus(
            foodId,
            FoodVectorOutboxOperation.UPSERT,
            FoodVectorOutboxStatus.PENDING,
        )
        if (!alreadyPending) {
            vectorOutboxRepository.save(FoodVectorOutbox.upsert(foodId))
        }
    }

    companion object {
        const val DEFAULT_CONTENT_REVIEW_TARGETS = 50

        const val MAX_CONTENT_REVIEW_TARGETS = 200
    }
}
