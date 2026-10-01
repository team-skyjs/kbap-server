package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodContentDraftJpaRepository
import com.kbap.common.domain.food.FoodIngredientJdbcRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentDraft
import com.kbap.common.domain.food.model.FoodContentDraftStatus
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class AdminFoodContentDraftService(
    private val foodRepository: FoodJpaRepository,
    private val draftRepository: FoodContentDraftJpaRepository,
    private val foodIngredientRepository: FoodIngredientJdbcRepository,
    private val ingestService: AdminFoodContentIngestService,
) {
    @Transactional(readOnly = true)
    fun getDraftPage(page: Int, size: Int): AdminFoodContentDraftPage {
        val drafts = draftRepository.findByReviewStatusOrderByIdAsc(FoodContentDraftStatus.PENDING, PageRequest.of(page, size))
        val foods = foodRepository.findAllById(drafts.content.map { it.foodId }).associateBy { it.id }
        return AdminFoodContentDraftPage(
            items = drafts.content.mapNotNull { draft -> foods[draft.foodId]?.let { food -> food to draft } },
            page = page,
            size = size,
            totalCount = drafts.totalElements,
        )
    }

    @Transactional(readOnly = true)
    fun getDraft(foodId: Long): Pair<Food, FoodContentDraft> {
        val food = foodRepository.findById(foodId).orElseThrow { BusinessException(ErrorCode.FOOD_NOT_FOUND) }
        val draft = draftRepository.findByFoodIdAndReviewStatus(foodId, FoodContentDraftStatus.PENDING)
            ?: throw BusinessException(ErrorCode.FOOD_CONTENT_DRAFT_NOT_FOUND)
        return food to draft
    }

    @Transactional
    fun reviewDraft(foodId: Long, passed: Boolean, reason: String?, adminAccountId: Long): FoodContentDraft {
        val food = foodRepository.findByIdForUpdate(foodId) ?: throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
        val draft = draftRepository.findByFoodIdAndReviewStatus(foodId, FoodContentDraftStatus.PENDING)
            ?: throw BusinessException(ErrorCode.FOOD_CONTENT_DRAFT_NOT_FOUND)
        if (passed) {
            verifyCatalog(draft)
            ingestService.applyDraft(food, draft)
            draft.approve(adminAccountId, LocalDateTime.now())
        } else {
            draft.reject(adminAccountId, LocalDateTime.now(), reason)
        }
        return draft
    }

    private fun verifyCatalog(draft: FoodContentDraft) {
        val codes = draft.ingredients.orEmpty().map { it.code }.toSet()
        if (foodIngredientRepository.findCatalogCodes(codes) != codes) throw BusinessException(ErrorCode.FOOD_UNKNOWN_INGREDIENT)
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
    }
}

data class AdminFoodContentDraftPage(
    val items: List<Pair<Food, FoodContentDraft>>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
)
