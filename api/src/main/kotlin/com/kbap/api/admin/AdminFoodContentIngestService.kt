package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.FoodIngredientJdbcRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminFoodContentIngestService(
    private val foodRepository: FoodJpaRepository,
    private val outboxRepository: FoodContentOutboxJpaRepository,
    private val foodIngredientRepository: FoodIngredientJdbcRepository,
    private val imageBatchItemRepository: ImageBatchItemJpaRepository,
    private val vectorOutboxRepository: FoodVectorOutboxJpaRepository,
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
        val regenerating = imageBatchItemRepository.findFoodIdsInRegeneration(listOf(foodId)).isNotEmpty()
        food.applyContent(
            description = description,
            longDescription = longDescription,
            spiciness = spiciness,
            nameTranslations = nameTranslations,
            descriptionTranslations = descriptionTranslations,
            ingredients = ingredients,
            keepStatus = regenerating,
        )
        foodIngredientRepository.replace(foodId, food.ingredients)
        if (food.isReady()) vectorOutboxRepository.enqueue(foodId, FoodVectorOutboxOperation.UPSERT)
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
        if (outbox.deadAt != null) {
            log.warn("포기한 요청의 결과가 도착해 버린다 — outboxId={}, foodId={}", outboxId, foodId)
            return null
        }
        if (outboxRepository.countSuperseded(outboxId) > 0) {
            log.warn("더 새 요청이 있는 옛 요청의 결과가 도착해 버린다 — outboxId={}, foodId={}", outboxId, foodId)
            return null
        }
        if (outbox.outboxStatus == FoodContentOutboxStatus.COMPLETE) {
            throw BusinessException(ErrorCode.FOOD_CONTENT_REQUEST_ALREADY_COMPLETED)
        }
        if (food == null) throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
        if (outboxRepository.completeIfProcessable(outboxId, foodId) != 1) {
            throw BusinessException(ErrorCode.INVALID_REQUEST)
        }
        return food
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(AdminFoodContentIngestService::class.java)
    }
}
