package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.admin.AdminAccountJpaRepository
import com.kbap.common.domain.admin.model.AdminAccount
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.Base64

@Service
class AdminHumanReviewService(
    private val foodRepository: FoodJpaRepository,
    private val adminAccountRepository: AdminAccountJpaRepository,
) {
    @Transactional
    fun markReviewed(foodId: Long, adminId: Long): AdminFoodHumanReviewResponse {
        val reviewer = adminAccountRepository.findById(adminId).orElse(null)
            ?: throw BusinessException(ErrorCode.INVALID_ACCESS_TOKEN)
        val food = getFoodForUpdate(foodId)
        food.markHumanReviewed(reviewer.id, LocalDateTime.now())
        return AdminFoodHumanReviewResponse(foodId = food.id, humanReview = humanReviewOf(food, mapOf(reviewer.id to reviewer)))
    }

    @Transactional
    fun clearReview(foodId: Long): AdminFoodHumanReviewResponse {
        val food = getFoodForUpdate(foodId)
        food.clearHumanReview()
        return AdminFoodHumanReviewResponse(foodId = food.id, humanReview = null)
    }

    @Transactional(readOnly = true)
    fun getHumanReviewPage(adminId: Long?, cursor: String?): AdminHumanReviewListResponse {
        val position = cursor?.let { HumanReviewCursor.decode(it) ?: throw BusinessException(ErrorCode.INVALID_CURSOR) }
        val rows = foodRepository.findHumanReviewedPage(adminId, position?.reviewedAt, position?.foodId, PAGE_SIZE + 1)
        val hasNext = rows.size > PAGE_SIZE
        val pageFoods = rows.take(PAGE_SIZE)
        val counts = foodRepository.countHumanReviewsByAdmin()
        val reviewers = reviewersOf(pageFoods.mapNotNull { it.humanReviewedBy } + counts.map { it.adminId })
        return AdminHumanReviewListResponse(
            items = pageFoods.map { food ->
                AdminHumanReviewItemResponse(
                    foodId = food.id,
                    name = food.displayName(LanguageCode.KO),
                    deleted = food.isDeleted(),
                    reviewer = reviewerResponseOf(reviewers, food.humanReviewedBy!!),
                    reviewedAt = food.humanReviewedAt!!,
                )
            },
            hasNext = hasNext,
            nextCursor = pageFoods.lastOrNull()?.takeIf { hasNext }?.let { HumanReviewCursor.encode(it.humanReviewedAt!!, it.id) },
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

    private fun getFoodForUpdate(foodId: Long): Food =
        foodRepository.findByIdForUpdate(foodId) ?: throw BusinessException(ErrorCode.FOOD_NOT_FOUND)

    companion object {
        const val PAGE_SIZE = 20
        const val UNKNOWN_REVIEWER = "(삭제된 관리자)"
    }
}

data class HumanReviewCursorPosition(val reviewedAt: LocalDateTime, val foodId: Long)

object HumanReviewCursor {
    fun encode(reviewedAt: LocalDateTime, foodId: Long): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$reviewedAt|$foodId".toByteArray())

    fun decode(raw: String): HumanReviewCursorPosition? = runCatching {
        val (at, id) = String(Base64.getUrlDecoder().decode(raw)).split('|', limit = 2)
        HumanReviewCursorPosition(LocalDateTime.parse(at), id.toLong())
    }.getOrNull()
}
