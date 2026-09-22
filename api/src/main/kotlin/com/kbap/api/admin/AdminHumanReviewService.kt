package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.admin.AdminAccountJpaRepository
import com.kbap.common.domain.admin.model.AdminAccount
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class AdminHumanReviewService(
    private val foodRepository: FoodJpaRepository,
    private val adminAccountRepository: AdminAccountJpaRepository,
) {
    @Transactional
    fun markReviewed(foodId: Long, adminId: Long): AdminFoodHumanReviewResponse {
        val reviewer = adminAccountRepository.findById(adminId).orElse(null)
            ?: throw BusinessException(ErrorCode.INVALID_ACCESS_TOKEN)
        val food = getFood(foodId)
        food.markHumanReviewed(reviewer.id, LocalDateTime.now())
        return AdminFoodHumanReviewResponse(foodId = food.id, humanReview = humanReviewOf(food, mapOf(reviewer.id to reviewer)))
    }

    @Transactional
    fun clearReview(foodId: Long): AdminFoodHumanReviewResponse {
        val food = getFood(foodId)
        food.clearHumanReview()
        return AdminFoodHumanReviewResponse(foodId = food.id, humanReview = null)
    }

    @Transactional(readOnly = true)
    fun getHumanReviewPage(adminId: Long?, cursor: Long?): AdminHumanReviewListResponse {
        val cursorFood = cursor?.let { foodRepository.findById(it).orElse(null) }
        val cursorAt = cursor?.let { cursorFood?.humanReviewedAt ?: throw BusinessException(ErrorCode.INVALID_CURSOR) }
        val rows = foodRepository.findHumanReviewedPage(adminId, cursorAt, cursor, PageRequest.of(0, PAGE_SIZE + 1))
        val hasNext = rows.size > PAGE_SIZE
        val pageFoods = rows.take(PAGE_SIZE)
        val counts = foodRepository.countHumanReviewsByAdmin()
        val reviewers = reviewersOf(pageFoods.mapNotNull { it.humanReviewedBy } + counts.map { it.adminId })
        return AdminHumanReviewListResponse(
            items = pageFoods.map { food ->
                AdminHumanReviewItemResponse(
                    foodId = food.id,
                    name = food.displayName(LanguageCode.KO),
                    reviewer = reviewerResponseOf(reviewers, food.humanReviewedBy!!),
                    reviewedAt = food.humanReviewedAt!!,
                )
            },
            hasNext = hasNext,
            nextCursor = pageFoods.lastOrNull()?.id?.takeIf { hasNext },
            summary = counts
                .sortedByDescending { it.count }
                .map { AdminHumanReviewSummaryResponse(adminId = it.adminId, displayName = reviewerResponseOf(reviewers, it.adminId).displayName, count = it.count) },
        )
    }

    @Transactional(readOnly = true)
    fun humanReviewsOf(foods: Collection<Food>): Map<Long, HumanReviewResponse> {
        val reviewers = reviewersOf(foods.mapNotNull { it.humanReviewedBy })
        return foods.mapNotNull { food -> humanReviewOf(food, reviewers)?.let { food.id to it } }.toMap()
    }

    @Transactional(readOnly = true)
    fun humanReviewOf(food: Food): HumanReviewResponse? = humanReviewsOf(listOf(food))[food.id]

    private fun humanReviewOf(food: Food, reviewers: Map<Long, AdminAccount>): HumanReviewResponse? {
        val by = food.humanReviewedBy ?: return null
        val at = food.humanReviewedAt ?: return null
        return HumanReviewResponse(reviewedBy = reviewerResponseOf(reviewers, by), reviewedAt = at)
    }

    private fun reviewersOf(ids: Collection<Long>): Map<Long, AdminAccount> =
        if (ids.isEmpty()) emptyMap() else adminAccountRepository.findAllById(ids.toSet()).associateBy { it.id }

    private fun reviewerResponseOf(reviewers: Map<Long, AdminAccount>, adminId: Long): AdminReviewerResponse =
        AdminReviewerResponse(id = adminId, displayName = reviewers[adminId]?.displayNameOrLoginId() ?: UNKNOWN_REVIEWER)

    private fun getFood(foodId: Long): Food =
        foodRepository.findById(foodId).orElse(null) ?: throw BusinessException(ErrorCode.FOOD_NOT_FOUND)

    companion object {
        const val PAGE_SIZE = 20
        const val UNKNOWN_REVIEWER = "(삭제된 관리자)"
    }
}
