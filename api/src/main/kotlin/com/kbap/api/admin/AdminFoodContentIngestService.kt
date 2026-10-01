package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodContentDraftJpaRepository
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.FoodIngredientJdbcRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentDraft
import com.kbap.common.domain.food.model.FoodContentDraftStatus
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class AdminFoodContentIngestService(
    private val foodRepository: FoodJpaRepository,
    private val outboxRepository: FoodContentOutboxJpaRepository,
    private val foodIngredientRepository: FoodIngredientJdbcRepository,
    private val imageBatchItemRepository: ImageBatchItemJpaRepository,
    private val vectorOutboxRepository: FoodVectorOutboxJpaRepository,
    private val draftRepository: FoodContentDraftJpaRepository,
) {
    @Transactional
    fun ingestContent(
        outboxId: Long,
        foodId: Long,
        description: String,
        longDescription: String?,
        spiciness: Int,
        nameTranslations: Map<String, String>,
        descriptionTranslations: Map<String, String>,
        ingredients: List<FoodIngredient>,
    ) {
        val food = lockFoodAndCompleteOutbox(outboxId, foodId) ?: return
        val storable = Food.storableIngredients(ingredients)
        draftRepository.findByFoodIdAndReviewStatus(foodId, FoodContentDraftStatus.PENDING)?.supersede()
        if (food.isReady()) {
            draftRepository.save(
                FoodContentDraft(
                    foodId = foodId,
                    outboxId = outboxId,
                    description = description,
                    longDescription = longDescription,
                    spiciness = spiciness,
                    nameTranslations = nameTranslations,
                    descriptionTranslations = descriptionTranslations,
                    ingredients = storable,
                ),
            )
            return
        }
        val regenerating = imageBatchItemRepository.findFoodIdsInRegeneration(listOf(foodId)).isNotEmpty()
        applyContent(food, description, longDescription, spiciness, nameTranslations, descriptionTranslations, ingredients, regenerating)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun applyDraft(food: Food, draft: FoodContentDraft) =
        applyContent(
            food,
            draft.description,
            draft.longDescription,
            draft.spiciness,
            draft.nameTranslations,
            draft.descriptionTranslations,
            draft.ingredients.orEmpty(),
            keepStatus = false,
        )

    private fun applyContent(
        food: Food,
        description: String,
        longDescription: String?,
        spiciness: Int,
        nameTranslations: Map<String, String>,
        descriptionTranslations: Map<String, String>,
        ingredients: List<FoodIngredient>,
        keepStatus: Boolean,
    ) {
        food.applyContent(
            description = description,
            longDescription = longDescription,
            spiciness = spiciness,
            nameTranslations = nameTranslations,
            descriptionTranslations = descriptionTranslations,
            ingredients = ingredients,
            keepStatus = keepStatus,
        )
        foodIngredientRepository.replace(food.id, food.ingredients)
        if (food.isReady()) vectorOutboxRepository.enqueue(food.id, FoodVectorOutboxOperation.UPSERT)
    }

    @Transactional
    fun ingestFailure(outboxId: Long, foodId: Long, failureKind: FoodContentFailureKind, reason: String) {
        val food = lockFoodAndCompleteOutbox(outboxId, foodId) ?: return
        val regenerating = imageBatchItemRepository.findFoodIdsInRegeneration(listOf(foodId)).isNotEmpty()
        food.recordContentFailure(failureKind, reason, keepStatus = regenerating)
    }

    private fun lockFoodAndCompleteOutbox(outboxId: Long, foodId: Long): Food? {
        val food = foodRepository.findByIdForUpdate(foodId)
        val outbox = outboxRepository.findById(outboxId).orElse(null)?.takeIf { it.foodId == foodId }
            ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        if (food == null || outboxRepository.findInFlightRequest(foodId)?.id != outboxId) {
            return rejectNotInFlight(outbox, food)
        }
        if (outboxRepository.completeIfProcessable(outboxId, foodId) != 1) {
            throw BusinessException(ErrorCode.INVALID_REQUEST)
        }
        return food
    }

    private fun rejectNotInFlight(outbox: FoodContentOutbox, food: Food?): Food? {
        if (outbox.deadAt != null) {
            log.warn("포기한 요청의 결과가 도착해 버린다 — outboxId={}, foodId={}", outbox.id, outbox.foodId)
            return null
        }
        if (outboxRepository.countSuperseded(outbox.id) > 0) {
            log.warn("더 새 요청이 있는 옛 요청의 결과가 도착해 버린다 — outboxId={}, foodId={}", outbox.id, outbox.foodId)
            return null
        }
        if (outbox.outboxStatus == FoodContentOutboxStatus.COMPLETE) {
            throw BusinessException(ErrorCode.FOOD_CONTENT_REQUEST_ALREADY_COMPLETED)
        }
        if (food == null) throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
        throw BusinessException(ErrorCode.INVALID_REQUEST)
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(AdminFoodContentIngestService::class.java)
    }
}
